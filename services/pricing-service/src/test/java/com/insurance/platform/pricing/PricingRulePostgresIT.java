package com.insurance.platform.pricing;

import com.insurance.platform.pricing.dto.*;
import com.insurance.platform.pricing.enums.*;
import com.insurance.platform.pricing.exception.PricingRuleException;
import com.insurance.platform.pricing.repository.PricingRuleRepository;
import com.insurance.platform.pricing.service.PricingRuleAdminService;
import com.insurance.platform.pricing.service.PricingService;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: ./mvnw -pl services/pricing-service -am -Ppostgres-it verify */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@Testcontainers
class PricingRulePostgresIT {
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17.11-bookworm");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);

    }
    @Autowired PricingRuleAdminService admin;
    @Autowired PricingService pricing;
    @Autowired PricingRuleRepository repository;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach void identity() {
        PricingTestIdentity.authenticate();
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.addHeader("X-Correlation-ID","x".repeat(128));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
    }
    @org.junit.jupiter.api.AfterEach void clearIdentity() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    private CreatePricingRuleRequest createRequest(Instant effectiveFrom, boolean enabled) {
        return new CreatePricingRuleRequest(RuleType.VEHICLE_VALUE,RuleOperator.GREATER_THAN,"0",null,
                new BigDecimal("2.0000"),null,effectiveFrom,enabled,"Isolated PostgreSQL test");
    }
    @Test void persistedCrudVersionsFiltersAndCalculation() {
        var now=Instant.now();
        var rule=admin.create(createRequest(now.plusSeconds(3600),false));
        assertThat(rule.version()).isZero();
        assertThat(rule.createdAt()).isNotNull().isEqualTo(rule.updatedAt());
        assertThat(admin.get(rule.id())).isEqualTo(rule);
        var page=admin.list(0,1,RuleType.VEHICLE_VALUE,false);
        assertThat(page.content()).extracting(PricingRuleResponse::id).contains(rule.id());
        var input=new PricingRequest(LocalDate.now(ZoneOffset.UTC).minusYears(30),8,
                Year.now(ZoneOffset.UTC).getValue()-5,new BigDecimal("700000"),1500,1,CoverageType.COMPREHENSIVE);
        assertThat(pricing.calculate(input).finalPremium()).isEqualByComparingTo("11200");
        rule=admin.setEnabled(rule.id(),new PricingRuleVersionRequest(rule.version()),true);
        assertThat(rule.version()).isEqualTo(1);
        assertThat(pricing.calculate(input).finalPremium()).isEqualByComparingTo("11200"); // future
        var id=rule.id(); var createdAt=rule.createdAt();
        var updated=admin.update(id,new UpdatePricingRuleRequest(rule.ruleType(),rule.operator(),rule.comparisonValue(),
                null,rule.factor(),null,now.minusSeconds(1),true,rule.description(),rule.version()));
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.createdAt()).isEqualTo(createdAt);
        assertThat(updated.updatedAt()).isAfter(rule.updatedAt());
        assertThat(pricing.calculate(input).finalPremium()).isEqualByComparingTo("22400");
        assertThatThrownBy(() -> admin.setEnabled(id,new PricingRuleVersionRequest(1L),false))
                .isInstanceOf(PricingRuleException.class).hasMessage("Pricing rule was modified by another request");
        var disabled=admin.setEnabled(id,new PricingRuleVersionRequest(2L),false);
        assertThat(disabled.version()).isEqualTo(3);
        assertThat(pricing.calculate(input).finalPremium()).isEqualByComparingTo("11200");
        var repeated=admin.setEnabled(id,new PricingRuleVersionRequest(3L),false);
        assertThat(repeated.version()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=?",Long.class,id.toString())).isEqualTo(5);
        var intents = jdbc.queryForList("select payload::text from outbox_events where aggregate_id=? order by created_at,id",String.class,id.toString());
        assertThat(jdbc.queryForList("select (payload->'data'->>'version')::int from outbox_events where aggregate_id=? order by created_at,id",Integer.class,id.toString())).containsExactly(0,1,2,3,4);
        assertThat(jdbc.queryForObject("select correlation_id from outbox_events where aggregate_id=? order by created_at,id limit 1",String.class,id.toString())).isEqualTo("x".repeat(128));
        for (var intent : intents) assertThat(intent).contains("pricing-service", "PricingRule", PricingTestIdentity.ADMIN.toString());
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success",Long.class)).isEqualTo(4);
    }

    @Test void simultaneousUpdatesCannotOverwriteEachOther() throws Exception {
        var rule=admin.create(createRequest(Instant.now().plusSeconds(3600),false));
        var barrier=new CyclicBarrier(2);
        Callable<String> update=() -> {
            try {
                new TransactionTemplate(transactions).execute(status -> {
                    var loaded=repository.findById(rule.id()).orElseThrow();
                    try { barrier.await(10,TimeUnit.SECONDS); }
                    catch(Exception ex) { throw new IllegalStateException(ex); }
                    PricingTestIdentity.authenticate();
                    admin.update(rule.id(),new UpdatePricingRuleRequest(loaded.getRuleType(),loaded.getOperator(),loaded.getComparisonValue(),
                            loaded.getComparisonValueTo(),loaded.getFactor(),loaded.getFixedAmount(),loaded.getEffectiveFrom(),true,loaded.getDescription(),loaded.getVersion()));
                    return null;
                });
                return "updated";
            } catch(OptimisticLockingFailureException ex) { return "conflict"; }
            finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        };
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(update); var second=executor.submit(update);
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("updated","conflict");
        }
        assertThat(admin.get(rule.id()).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=? and event_type='PricingRuleUpdated'",Integer.class,rule.id().toString())).isEqualTo(1);
    }

    @Test void outboxFailureRollsBackEveryMutationAndOuterRollbackRemovesIntent() {
        var rule=admin.create(createRequest(Instant.now().plusSeconds(3600),false));
        var before=jdbc.queryForMap("select * from pricing_rules where id=?",rule.id());
        int count=jdbc.queryForObject("select count(*) from outbox_events",Integer.class);
        jdbc.execute("alter table outbox_events add constraint phase20_failure check(false) NOT VALID");
        try {
            assertThatThrownBy(()->admin.create(createRequest(Instant.now(),false))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->admin.update(rule.id(),new UpdatePricingRuleRequest(rule.ruleType(),rule.operator(),rule.comparisonValue(),null,
                    rule.factor(),null,rule.effectiveFrom(),true,"Changed",rule.version()))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->admin.setEnabled(rule.id(),new PricingRuleVersionRequest(rule.version()),true)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->admin.setEnabled(rule.id(),new PricingRuleVersionRequest(rule.version()),false)).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForMap("select * from pricing_rules where id=?",rule.id())).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from outbox_events",Integer.class)).isEqualTo(count);
        } finally { jdbc.execute("alter table outbox_events drop constraint phase20_failure"); }
        var id=new java.util.concurrent.atomic.AtomicReference<java.util.UUID>();
        new TransactionTemplate(transactions).executeWithoutResult(tx->{id.set(admin.create(createRequest(Instant.now(),false)).id());tx.setRollbackOnly();});
        assertThat(repository.findById(id.get())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=?",Integer.class,id.get().toString())).isZero();
    }

    @Test void pendingBatchesAreBoundedOrderedLockedAndImmutable() throws Exception {
        admin.create(createRequest(Instant.now().plusSeconds(3600),false));
        admin.create(createRequest(Instant.now().plusSeconds(3600),false));
        var poller=new com.insurance.platform.pricing.repository.OutboxPollingRepository(jdbc.getDataSource());
        try(var batch=poller.claim(1).orElseThrow()) {
            assertThat(batch.events()).hasSize(1);
            var row=batch.events().getFirst();
            assertThat(row.id().toString()).isEqualTo(jdbc.queryForObject("select id::text from outbox_events where status='PENDING' order by created_at,id limit 1",String.class));
            assertThat(poller.claim(1)).isEmpty();
            batch.failed(row.id());batch.published(row.id(),Instant.now());
            assertThat(jdbc.queryForObject("select payload::text from outbox_events where id=?",String.class,row.id())).isEqualTo(row.payload());
            assertThat(jdbc.queryForObject("select event_id from outbox_events where id=?",java.util.UUID.class,row.id())).isEqualTo(row.eventId());
            assertThat(jdbc.queryForObject("select retry_count from outbox_events where id=?",Integer.class,row.id())).isEqualTo(1);
        }
    }
}
