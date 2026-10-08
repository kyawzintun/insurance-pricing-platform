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
        properties.add("auth.jwt.secret", AdminJwtSupport::secret);
    }
    @Autowired PricingRuleAdminService admin;
    @Autowired PricingService pricing;
    @Autowired PricingRuleRepository repository;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

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
        assertThat(jdbc.queryForObject("select count(*) from outbox_events",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success",Long.class)).isEqualTo(3);
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
                    loaded.changeEnabled(true,Instant.now());
                    repository.flush();
                    return null;
                });
                return "updated";
            } catch(OptimisticLockingFailureException ex) { return "conflict"; }
        };
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(update); var second=executor.submit(update);
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("updated","conflict");
        }
        assertThat(admin.get(rule.id()).version()).isEqualTo(1);
    }
}
