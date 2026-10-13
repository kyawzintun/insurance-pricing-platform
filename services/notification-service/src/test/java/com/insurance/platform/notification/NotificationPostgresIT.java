package com.insurance.platform.notification;

import com.insurance.platform.notification.event.QuoteEventDecoder;
import com.insurance.platform.notification.service.NotificationService;
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
import static com.insurance.platform.notification.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@ActiveProfiles("integration")
@Testcontainers
class NotificationPostgresIT {
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17.11-bookworm");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DATABASE::getJdbcUrl);
        r.add("spring.datasource.username", DATABASE::getUsername);
        r.add("spring.datasource.password", DATABASE::getPassword);
    }
    @Autowired NotificationService service;
    @Autowired QuoteEventDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    @BeforeEach void setup() { jdbc.update("DELETE FROM notifications"); when(clock.instant()).thenReturn(NOW); }
    @AfterEach void untouched() { assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isZero(); }
    @Test void realInsertCommitsAllFields() {
        service.create(decoder.decode(json()).orElseThrow());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT * FROM notifications");
        assertThat(row.get("id")).isInstanceOf(UUID.class);
        assertThat(row.get("event_id")).isEqualTo(EVENT);
        assertThat(row.get("quote_id")).isEqualTo(QUOTE);
        assertThat(row.get("customer_id")).isEqualTo(CUSTOMER);
        assertThat(row.get("type")).isEqualTo("QUOTE_PRICED");
        assertThat(row.get("status")).isEqualTo("CREATED");
        assertThat(row.get("message")).isEqualTo("Your quote Q-TEST-123 is ready. Premium: 11200.00 THB.");
        assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(NOW);
        assertThat(((java.sql.Timestamp) row.get("processed_at")).toInstant()).isEqualTo(NOW);
    }
    @Test void duplicateFailsWithoutOverwritingOrTreatingItAsSuccess() {
        var event = decoder.decode(json()).orElseThrow(); service.create(event);
        assertThatThrownBy(() -> service.create(event)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(1);
    }
    @Test void transactionFailureRollsBackEarlierInsert() {
        var event = decoder.decode(json()).orElseThrow();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.create(event); service.create(event);
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
    }
}
