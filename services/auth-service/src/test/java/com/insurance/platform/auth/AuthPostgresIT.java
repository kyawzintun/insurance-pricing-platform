package com.insurance.platform.auth;
import com.insurance.platform.auth.dto.RegisterRequest;
import com.insurance.platform.auth.service.AuthService;
import com.insurance.platform.auth.repository.OutboxPollingRepository;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest
@ActiveProfiles("integration")
@Testcontainers
class AuthPostgresIT {
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17.11-bookworm");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DATABASE::getJdbcUrl);
        r.add("spring.datasource.username", DATABASE::getUsername);
        r.add("spring.datasource.password", DATABASE::getPassword);
        r.add("auth.jwt.secret", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired AuthService service;
    @Autowired com.insurance.platform.auth.service.CustomerAdminService customers;
    @Autowired com.insurance.platform.auth.repository.UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired javax.sql.DataSource datasource;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @MockitoBean Clock clock;
    static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    static RegisterRequest request(String email) { return new RegisterRequest(email, "Test-password-42!", "Test", "Customer"); }
    @BeforeEach void reset() {
        jdbc.update("delete from outbox_events"); jdbc.update("delete from users"); when(clock.instant()).thenReturn(NOW);
    }
    void counts(int users, int events) {
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(users);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isEqualTo(events);
    }
    @Test void registrationIsAtomicAndSensitiveFieldsAreExcluded() {
        var attributes = new org.springframework.mock.web.MockHttpServletRequest();
        attributes.addHeader("X-Correlation-ID", "x".repeat(128));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(attributes));
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
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
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

    @AfterEach void clearAdminContext() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test void customerPaginationScopeTransitionsAndLoginRegression() {
        AuthTestIdentity.authenticate();
        var first=service.register(request("one@example.com"));var second=service.register(request("two@example.com"));
        users.saveAndFlush(new com.insurance.platform.auth.entity.User(AuthTestIdentity.ADMIN,"admin@example.com","not-a-login-hash","Admin","User",
                com.insurance.platform.auth.enums.Role.ADMIN,com.insurance.platform.auth.enums.UserStatus.ACTIVE,NOW));
        var list=customers.list(0,100);assertThat(list.totalElements()).isEqualTo(2);
        assertThat(list.content()).extracting(com.insurance.platform.auth.dto.CustomerResponse::id).doesNotContain(AuthTestIdentity.ADMIN);
        var expected=java.util.stream.Stream.of(first.id(),second.id()).sorted(java.util.Comparator.comparing(java.util.UUID::toString).reversed()).toList();
        assertThat(list.content()).extracting(com.insurance.platform.auth.dto.CustomerResponse::id).containsExactlyElementsOf(expected);
        assertThat(customers.list(0,1).totalPages()).isEqualTo(2);assertThat(customers.list(2,1).content()).isEmpty();
        assertThat(customers.get(first.id()).email()).isEqualTo("one@example.com");counts(3,2);
        assertThatThrownBy(()->customers.get(AuthTestIdentity.ADMIN)).hasMessage("Only CUSTOMER accounts can be managed");
        assertThatThrownBy(()->customers.setEnabled(AuthTestIdentity.ADMIN,false)).hasMessage("Only CUSTOMER accounts can be managed");
        var credentials=new com.insurance.platform.auth.dto.LoginRequest(first.email(),"Test-password-42!");
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
        assertThatThrownBy(()->customers.setEnabled(java.util.UUID.randomUUID(),false)).hasMessage("Customer not found");counts(1,1);
        customers.setEnabled(user.id(),false);
        jdbc.execute("alter table outbox_events add constraint phase21_enable_failure check(event_type<>'UserEnabled')");
        try {
            assertThatThrownBy(()->customers.setEnabled(user.id(),true)).isInstanceOf(RuntimeException.class);
            assertThat(customers.get(user.id()).status().name()).isEqualTo("DISABLED");counts(1,2);
        } finally { jdbc.execute("alter table outbox_events drop constraint phase21_enable_failure"); }
    }

    @Test void concurrentSameStatusCommandsCreateExactlyOneIntentPerTransition() throws Exception {
        var user=service.register(request("concurrent@example.com"));
        for(boolean enabled:java.util.List.of(false,true)) {
            var barrier=new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<String> call=()->{
                AuthTestIdentity.authenticate();
                try { barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return customers.setEnabled(user.id(),enabled).status().name(); }
                finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
            };
            try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var a=executor.submit(call);var b=executor.submit(call);
                assertThat(java.util.List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS)))
                        .containsOnly(enabled?"ACTIVE":"DISABLED");
            }
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type=?",Integer.class,enabled?"UserEnabled":"UserDisabled")).isEqualTo(1);
        }
        counts(1,3);
    }
}
