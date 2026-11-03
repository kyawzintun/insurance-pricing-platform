package com.insurance.platform.auth;
import com.insurance.platform.auth.config.RsaKeys;
import com.insurance.platform.auth.dto.CustomerResponse;
import com.insurance.platform.auth.dto.LoginRequest;
import com.insurance.platform.auth.dto.RegisterRequest;
import com.insurance.platform.auth.entity.User;
import com.insurance.platform.auth.enums.Role;
import com.insurance.platform.auth.enums.UserStatus;
import com.insurance.platform.auth.exception.AuthException;
import com.insurance.platform.auth.repository.OutboxPollingRepository;
import com.insurance.platform.auth.repository.UserRepository;
import com.insurance.platform.auth.service.AuthService;
import com.insurance.platform.auth.service.CustomerAdminService;
import com.insurance.platform.auth.service.RefreshSessionService;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
@SpringBootTest
@ActiveProfiles("integration")
@Testcontainers
class AuthPostgresIT {
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17.11-bookworm");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DATABASE::getJdbcUrl);
        r.add("spring.datasource.username", DATABASE::getUsername);
        r.add("spring.datasource.password", DATABASE::getPassword);

    }
    @Autowired RefreshSessionService sessions;
    @Autowired AuthService service;
    @Autowired CustomerAdminService customers;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource datasource;
    @Autowired PasswordEncoder passwords;
    @MockitoBean Clock clock;
    static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    static RegisterRequest request(String email) { return new RegisterRequest(email, "Test-password-42!", "Test", "Customer"); }
    @BeforeEach void reset() {
        jdbc.update("delete from refresh_tokens"); jdbc.update("delete from outbox_events"); jdbc.update("delete from users"); when(clock.instant()).thenReturn(NOW);
    }
    void counts(int users, int events) {
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(users);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isEqualTo(events);
    }
    @Test void refreshRotationLogoutExpiryDisabledAndRollback() {
        var user=service.register(request("session@example.com"));
        var login=sessions.login(new LoginRequest("session@example.com","Test-password-42!"));
        assertThat(login.cookieToken).matches("[A-Za-z0-9_-]{43}");
        assertThat(jdbc.queryForObject("select token_hash from refresh_tokens",String.class)).hasSize(64).isNotEqualTo(login.cookieToken);
        var next=sessions.refresh(login.cookieToken);
        for (var access : List.of(login.access, next.access)) {
            try {
                var signed = SignedJWT.parse(access.accessToken());
                assertThat(signed.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
                assertThat(signed.verify(new RSASSAVerifier(
                    RsaKeys.publicKey(new ClassPathResource("keys/dev-jwt-public.pem")))))
                    .isTrue();
            } catch (Exception ex) { throw new AssertionError("RSA token verification failed", ex); }
        }
        assertThat(next.cookieToken).isNotEqualTo(login.cookieToken);
        assertThat(jdbc.queryForObject("select expires_at from refresh_tokens where revoked_at is null",OffsetDateTime.class).toInstant())
            .isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is not null",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->sessions.refresh(login.cookieToken)).isInstanceOf(AuthException.class);
        jdbc.execute("alter table refresh_tokens add constraint phase23_failure check (revoked_at is not null) not valid");
        try { assertThatThrownBy(()->sessions.refresh(next.cookieToken)).isInstanceOf(RuntimeException.class); }
        finally {jdbc.execute("alter table refresh_tokens drop constraint phase23_failure");}
        var recovered=sessions.refresh(next.cookieToken);
        sessions.logout(recovered.cookieToken); sessions.logout(recovered.cookieToken);sessions.logout(null);sessions.logout("invalid");
        assertThatThrownBy(()->sessions.refresh(recovered.cookieToken)).isInstanceOf(AuthException.class);
        var expiring=sessions.login(new LoginRequest("session@example.com","Test-password-42!"));
        jdbc.update("update refresh_tokens set expires_at=?",Timestamp.from(NOW.minusSeconds(1)));
        assertThatThrownBy(()->sessions.refresh(expiring.cookieToken)).isInstanceOf(AuthException.class);
        var disabled=sessions.login(new LoginRequest("session@example.com","Test-password-42!"));
        jdbc.update("update users set status='DISABLED' where id=?",user.id());
        assertThatThrownBy(()->sessions.refresh(disabled.cookieToken)).isInstanceOf(AuthException.class);
        jdbc.update("update users set status='ACTIVE' where id=?",user.id());
        assertThatThrownBy(()->sessions.refresh(disabled.cookieToken)).isInstanceOf(AuthException.class);
    }
    @Test void concurrentRefreshHasOneWinner() throws Exception {
        service.register(request("race-session@example.com"));
        var login=sessions.login(new LoginRequest("race-session@example.com","Test-password-42!"));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
          Callable<Boolean> attempt=()->{start.await();try{sessions.refresh(login.cookieToken);return true;}catch(AuthException e){return false;}};
          var a=pool.submit(attempt);var b=pool.submit(attempt);start.countDown();
          assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void registrationIsAtomicAndSensitiveFieldsAreExcluded() {
        var attributes = new MockHttpServletRequest();
        attributes.addHeader("X-Correlation-ID", "x".repeat(128));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(attributes));
        try {
            var user = service.register(request(" TEST@Example.COM "));
            counts(1,1);
            var row = jdbc.queryForMap("select * from outbox_events");
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("correlation_id")).isEqualTo("x".repeat(128));
            var payload = row.get("payload").toString();
            assertThat(payload).contains("test@example.com", "UserRegistered", "CUSTOMER", "ACTIVE", user.id().toString(), NOW.toString())
                    .doesNotContain("password", "passwordHash", "$2", "accessToken", "refreshToken", "Authorization", "firstName", "lastName");
            assertThat(passwords.matches("Test-password-42!", jdbc.queryForObject("select password_hash from users", String.class))).isTrue();
            assertThatThrownBy(() -> service.register(request("test@example.com"))).isInstanceOf(RuntimeException.class);
            counts(1,1);
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
    @Test void failedOutboxInsertRollsBackUser() {
        jdbc.execute("alter table outbox_events add constraint phase19_failure check (event_type <> 'UserRegistered')");
        try { assertThatThrownBy(() -> service.register(request("rollback@example.com"))).isInstanceOf(RuntimeException.class); counts(0,0); }
        finally { jdbc.execute("alter table outbox_events drop constraint phase19_failure"); }
    }
    @Test void outerRollbackRemovesBothWrites() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { service.register(request("outer@example.com")); counts(1,1); tx.setRollbackOnly(); });
        counts(0,0);
    }
    @Test void orderedBoundedPollingLockAndImmutableTransitions() throws Exception {
        service.register(request("one@example.com")); service.register(request("two@example.com"));
        var poller = new OutboxPollingRepository(datasource);
        try (var batch = poller.claim(1).orElseThrow()) {
            assertThat(batch.events()).hasSize(1);
            assertThat(batch.events().getFirst().id().toString()).isEqualTo(jdbc.queryForObject("select id::text from outbox_events order by created_at,id limit 1",String.class));
            assertThat(poller.claim(1)).isEmpty();
            var event = batch.events().getFirst();
            batch.failed(event.id());
            assertThat(jdbc.queryForObject("select retry_count from outbox_events where id=?", Integer.class,event.id())).isEqualTo(1);
            batch.published(event.id(),NOW);
            assertThat(jdbc.queryForObject("select payload::text from outbox_events where id=?",String.class,event.id())).isEqualTo(event.payload());
        }
        try (var batch = poller.claim(50).orElseThrow()) { assertThat(batch.events()).hasSize(1); }
    }

    @AfterEach void clearAdminContext() { SecurityContextHolder.clearContext(); }

    @Test void customerPaginationScopeTransitionsAndLoginRegression() {
        AuthTestIdentity.authenticate();
        var first=service.register(request("one@example.com"));var second=service.register(request("two@example.com"));
        users.saveAndFlush(new User(AuthTestIdentity.ADMIN,"admin@example.com","not-a-login-hash","Admin","User",
                Role.ADMIN,UserStatus.ACTIVE,NOW));
        var list=customers.list(0,100);assertThat(list.totalElements()).isEqualTo(2);
        assertThat(list.content()).extracting(CustomerResponse::id).doesNotContain(AuthTestIdentity.ADMIN);
        var expected=Stream.of(first.id(),second.id()).sorted(Comparator.comparing(UUID::toString).reversed()).toList();
        assertThat(list.content()).extracting(CustomerResponse::id).containsExactlyElementsOf(expected);
        assertThat(customers.list(0,1).totalPages()).isEqualTo(2);assertThat(customers.list(2,1).content()).isEmpty();
        assertThat(customers.get(first.id()).email()).isEqualTo("one@example.com");counts(3,2);
        assertThatThrownBy(()->customers.get(AuthTestIdentity.ADMIN)).hasMessage("Only CUSTOMER accounts can be managed");
        assertThatThrownBy(()->customers.setEnabled(AuthTestIdentity.ADMIN,false)).hasMessage("Only CUSTOMER accounts can be managed");
        var credentials=new LoginRequest(first.email(),"Test-password-42!");
        assertThat(service.login(credentials).accessToken()).isNotBlank();
        var before=jdbc.queryForMap("select * from users where id=?",first.id());
        var disabled=customers.setEnabled(first.id(),false);assertThat(disabled.status().name()).isEqualTo("DISABLED");
        assertThat(disabled.updatedAt()).isEqualTo(NOW);assertThat(customers.setEnabled(first.id(),false)).isEqualTo(disabled);
        assertThatThrownBy(()->service.login(credentials)).hasMessage("Invalid credentials");
        customers.setEnabled(first.id(),true);customers.setEnabled(first.id(),true);
        assertThat(service.login(credentials).accessToken()).isNotBlank();
        assertThat(jdbc.queryForObject("select password_hash from users where id=?",String.class,first.id())).isEqualTo(before.get("password_hash"));
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type='UserDisabled'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type='UserEnabled'",Integer.class)).isEqualTo(1);
        counts(3,4);
    }

    @Test void statusAndOutboxRollbackTogetherAndInvalidTargetHasNoIntent() {
        AuthTestIdentity.authenticate();var user=service.register(request("rollback-status@example.com"));
        var before=jdbc.queryForMap("select * from users where id=?",user.id());
        jdbc.execute("alter table users add constraint phase21_status_failure check(status='ACTIVE') NOT VALID");
        try {
            assertThatThrownBy(()->customers.setEnabled(user.id(),false)).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForMap("select * from users where id=?",user.id())).isEqualTo(before);counts(1,1);
        } finally { jdbc.execute("alter table users drop constraint phase21_status_failure"); }
        jdbc.execute("alter table outbox_events add constraint phase21_failure check(event_type='UserRegistered')");
        try {
            assertThatThrownBy(()->customers.setEnabled(user.id(),false)).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForMap("select * from users where id=?",user.id())).isEqualTo(before);counts(1,1);
        } finally { jdbc.execute("alter table outbox_events drop constraint phase21_failure"); }
        new TransactionTemplate(transactions).executeWithoutResult(tx->{customers.setEnabled(user.id(),false);tx.setRollbackOnly();});
        assertThat(jdbc.queryForMap("select * from users where id=?",user.id())).isEqualTo(before);counts(1,1);
        assertThatThrownBy(()->customers.setEnabled(UUID.randomUUID(),false)).hasMessage("Customer not found");counts(1,1);
        customers.setEnabled(user.id(),false);
        jdbc.execute("alter table outbox_events add constraint phase21_enable_failure check(event_type<>'UserEnabled')");
        try {
            assertThatThrownBy(()->customers.setEnabled(user.id(),true)).isInstanceOf(RuntimeException.class);
            assertThat(customers.get(user.id()).status().name()).isEqualTo("DISABLED");counts(1,2);
        } finally { jdbc.execute("alter table outbox_events drop constraint phase21_enable_failure"); }
    }

    @Test void concurrentSameStatusCommandsCreateExactlyOneIntentPerTransition() throws Exception {
        var user=service.register(request("concurrent@example.com"));
        for(boolean enabled:List.of(false,true)) {
            var barrier=new CyclicBarrier(2);
            Callable<String> call=()->{
                AuthTestIdentity.authenticate();
                try { barrier.await(10,TimeUnit.SECONDS);return customers.setEnabled(user.id(),enabled).status().name(); }
                finally { SecurityContextHolder.clearContext(); }
            };
            try(var executor=Executors.newFixedThreadPool(2)) {
                var a=executor.submit(call);var b=executor.submit(call);
                assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)))
                        .containsOnly(enabled?"ACTIVE":"DISABLED");
            }
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type=?",Integer.class,enabled?"UserEnabled":"UserDisabled")).isEqualTo(1);
        }
        counts(1,3);
    }
}
