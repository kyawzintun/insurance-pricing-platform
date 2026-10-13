package com.insurance.platform.audit;

import com.insurance.platform.audit.event.QuoteEventDecoder;
import com.insurance.platform.audit.service.AuditService;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.insurance.platform.audit.AuditFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@ActiveProfiles("integration")
@Testcontainers
class AuditPostgresIT {
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17.11-bookworm");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DATABASE::getJdbcUrl);
        r.add("spring.datasource.username", DATABASE::getUsername);
        r.add("spring.datasource.password", DATABASE::getPassword);
    }
    @Autowired AuditService service;
    @Autowired QuoteEventDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    @BeforeEach void setup() { jdbc.update("DELETE FROM audit_records"); when(clock.instant()).thenReturn(NOW); }
    @AfterEach void untouched() { assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isZero(); }
    @Test void realInsertCommitsAllFields() {
        service.create(decoder.decode(json()).orElseThrow());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_records", Long.class)).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT * FROM audit_records");
        assertThat(row.get("id")).isInstanceOf(UUID.class);
        assertThat(row.get("event_id")).isEqualTo(EVENT);
        assertThat(row.get("event_type")).isEqualTo("QuotePriced");
        assertThat(row.get("actor_type")).isEqualTo("CUSTOMER");
        assertThat(row.get("actor_id")).isEqualTo(CUSTOMER.toString());
        assertThat(row.get("entity_type")).isEqualTo("Quote");
        assertThat(row.get("entity_id")).isEqualTo(QUOTE.toString());
        assertThat(row.get("correlation_id")).isEqualTo("phase14-test");
        assertThat(row.get("producer")).isEqualTo("quote-service");
        assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(java.time.Instant.parse("2026-10-12T09:00:00Z"));
        assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(NOW);
        AuditEventTests.assertMetadata(row.get("metadata").toString());

    }
    @Test void complete128CharacterCorrelationIdSurvivesWithoutTruncation() {
        var correlation = "c".repeat(128);
        service.create(decoder.decode(json().replace("phase14-test", correlation)).orElseThrow());
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM audit_records", String.class)).isEqualTo(correlation);
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='audit_records' AND column_name='metadata'", String.class)).isEqualTo("jsonb");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='3' AND success", Long.class)).isEqualTo(1);
    }
    @Test void duplicateFailsWithoutOverwritingOrTreatingItAsSuccess() {
        var event = decoder.decode(json()).orElseThrow(); service.create(event);
        assertThatThrownBy(() -> service.create(event)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_records", Long.class)).isEqualTo(1);
    }
    @Test void transactionFailureRollsBackEarlierInsert() {
        var event = decoder.decode(json()).orElseThrow();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.create(event); service.create(event);
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_records", Long.class)).isZero();
    }
}
