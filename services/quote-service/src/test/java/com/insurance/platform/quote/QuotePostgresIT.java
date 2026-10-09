package com.insurance.platform.quote;

import com.insurance.platform.quote.client.PricingClient;
import com.insurance.platform.quote.dto.VehicleSnapshot;
import com.insurance.platform.quote.exception.QuoteException;
import com.insurance.platform.quote.service.QuoteService;
import com.insurance.platform.quote.service.QuotePersistenceService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.insurance.platform.quote.QuoteFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in disposable PostgreSQL only: -Ppostgres-it verify. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@Testcontainers
class QuotePostgresIT {
    @Container static final PostgreSQLContainer DATABASE=new PostgreSQLContainer("postgres:17.11-bookworm");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",DATABASE::getUsername);
        registry.add("spring.datasource.password",DATABASE::getPassword);
        registry.add("auth.jwt.secret",QuoteJwtSupport::secret);
    }
    @Autowired QuoteService service;
    @Autowired QuotePersistenceService persistence;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PricingClient pricing;
    @MockitoBean Clock clock;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        when(pricing.calculate(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return price();
        });
    }
    private Map<String,Long> counts() {
        var values=new LinkedHashMap<String,Long>();
        for(String table:List.of("quotes","quote_drivers","quote_vehicles","pricing_breakdowns","pricing_adjustments","outbox_events")) {
            values.put(table,jdbc.queryForObject("select count(*) from "+table,Long.class));
        }
        return values;
    }
    @Test void completeGraphPersistsWithExactSnapshotsAndThirtyDays() {
        var customer=UUID.randomUUID();
        var response=service.create(customer,request());
        var quote=jdbc.queryForMap("select * from quotes where id=?",response.id());
        assertThat(quote.get("customer_id")).isEqualTo(customer);
        assertThat(quote.get("status")).isEqualTo("PRICED");
        assertThat((BigDecimal)quote.get("premium_amount")).isEqualTo(new BigDecimal("11200.00"));
        assertThat(response.quoteReference()).hasSize(43);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(jdbc.queryForObject("select extract(epoch from expires_at-priced_at) from quotes where id=?",BigDecimal.class,response.id()))
                .isEqualByComparingTo("2592000");
        var driver=jdbc.queryForMap("select * from quote_drivers where quote_id=?",response.id());
        assertThat(driver.get("date_of_birth").toString()).isEqualTo("1995-04-20");
        assertThat(driver.get("driving_experience_years")).isEqualTo(8);
        assertThat(driver.get("previous_claims_count")).isEqualTo(1);
        var vehicle=jdbc.queryForMap("select * from quote_vehicles where quote_id=?",response.id());
        assertThat(vehicle.get("brand_id")).isEqualTo(BRAND);
        assertThat(vehicle.get("model_id")).isEqualTo(MODEL);
        assertThat(vehicle.get("brand_name")).isEqualTo("Toyota");
        assertThat(vehicle.get("model_name")).isEqualTo("Camry");
        assertThat((BigDecimal)vehicle.get("vehicle_value")).isEqualTo(new BigDecimal("700000.00"));
        var breakdown=jdbc.queryForMap("select * from pricing_breakdowns where quote_id=?",response.id());
        assertThat((BigDecimal)breakdown.get("base_premium")).isEqualTo(new BigDecimal("8000.00"));
        assertThat(breakdown.get("calculated_at").toString()).contains("2026-10-08");
        var adjustments=jdbc.queryForList("select * from pricing_adjustments where pricing_breakdown_id=? order by sequence_number",breakdown.get("id"));
        assertThat(adjustments).hasSize(1);
        assertThat(adjustments.getFirst().get("pricing_rule_id")).isEqualTo(price().adjustments().getFirst().pricingRuleId());
        assertThat(adjustments.getFirst().get("sequence_number")).isEqualTo(1);
        assertThat((BigDecimal)adjustments.getFirst().get("factor")).isEqualTo(new BigDecimal("1.4000"));
        assertThat(jdbc.queryForObject("select count(*) from outbox_events",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success",Long.class)).isEqualTo(5);
        // Existing unique quote reference and one-driver-per-quote constraints are real PostgreSQL constraints.
        assertThatThrownBy(() -> jdbc.update("insert into quotes select ?,quote_reference,customer_id,status,coverage_type,premium_amount,currency,created_at,priced_at,expires_at,updated_at from quotes where id=?",UUID.randomUUID(),response.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into quote_drivers select ?,quote_id,date_of_birth,driving_experience_years,previous_claims_count from quote_drivers where quote_id=?",UUID.randomUUID(),response.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void childDatabaseFailureRollsBackEntireAggregate() {
        var before=counts();
        var invalidVehicle=new VehicleSnapshot(BRAND,"X".repeat(101),MODEL,"Camry",2021,new BigDecimal("700000"),1500);
        assertThatThrownBy(() -> persistence.create(UUID.randomUUID(),request(),invalidVehicle,price()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(counts()).isEqualTo(before);
    }
    @Test void pricingFailureLeavesNoRows() {
        var before=counts();
        when(pricing.calculate(any())).thenThrow(QuoteException.pricingUnavailable());
        assertThatThrownBy(() -> service.create(UUID.randomUUID(),request())).isInstanceOf(QuoteException.class);
        assertThat(counts()).isEqualTo(before);
    }
    @Test void sequentialQuotesHaveUniqueReferencesAndIndependentSnapshots() {
        var first=service.create(UUID.randomUUID(),request());
        var second=service.create(UUID.randomUUID(),request());
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.quoteReference()).isNotEqualTo(second.quoteReference());
        var changed=new com.insurance.platform.quote.client.PricingResult(price().basePricingRuleId(),1L,new BigDecimal("9000.00"),new BigDecimal("9000.00"),"THB",NOW,List.of());
        when(pricing.calculate(any())).thenReturn(changed);
        var third=service.create(UUID.randomUUID(),request());
        assertThat(third.pricing().finalPremium()).isEqualByComparingTo("9000");
        assertThat(jdbc.queryForObject("select premium_amount from quotes where id=?",BigDecimal.class,first.id())).isEqualByComparingTo("11200");
    }
}
