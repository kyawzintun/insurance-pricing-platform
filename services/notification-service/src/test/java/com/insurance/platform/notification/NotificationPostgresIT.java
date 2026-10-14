package com.insurance.platform.notification;

import com.insurance.platform.notification.event.QuoteEventDecoder;
import com.insurance.platform.notification.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.*;
import com.insurance.platform.notification.service.ProcessResult;
import com.insurance.platform.notification.repository.ProcessedEventRepository;
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
    @Autowired ProcessedEventRepository markers;
    @MockitoBean Clock clock;
    @BeforeEach void setup() { jdbc.update("DELETE FROM notifications"); jdbc.update("DELETE FROM processed_events"); when(clock.instant()).thenReturn(NOW); }
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
    @Test void duplicatePreservesRowsAndOriginalProcessedTime() {
        var event = decoder.decode(json()).orElseThrow();
        assertThat(service.create(event)).isEqualTo(ProcessResult.PROCESSED);
        var original = jdbc.queryForMap("SELECT * FROM notifications");
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertThat(service.create(event)).isEqualTo(ProcessResult.DUPLICATE);
        assertCounts(1, 1);
        assertThat(jdbc.queryForMap("SELECT * FROM notifications")).isEqualTo(original);
        var marker = jdbc.queryForMap("SELECT * FROM processed_events");
        assertThat(marker.get("event_id")).isEqualTo(EVENT);
        assertThat(marker.get("consumer_name")).isEqualTo(NotificationService.CONSUMER_NAME);
        assertThat(((java.sql.Timestamp) marker.get("processed_at")).toInstant()).isEqualTo(NOW);
    }
    @Test void differentEventIdsBothProcessAndBusinessUniquenessRemains() {
        service.create(decoder.decode(json()).orElseThrow());
        var second = UUID.randomUUID();
        service.create(decoder.decode(json().replace(EVENT.toString(), second.toString())).orElseThrow());
        assertCounts(2, 2);
        assertThatThrownBy(() -> jdbc.update("UPDATE notifications SET event_id=? WHERE event_id=?", EVENT, second))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertCounts(2, 2);
    }
    @Test void markerIdentityIncludesStableConsumerName() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(markers.tryMarkProcessed(EVENT, "first-consumer", NOW)).isTrue();
            assertThat(markers.tryMarkProcessed(EVENT, "second-consumer", NOW)).isTrue();
            assertThat(markers.tryMarkProcessed(EVENT, "first-consumer", NOW.plusSeconds(1))).isFalse();
        });
        assertCounts(0, 2);
    }
    @Test void markerCannotCommitOutsideBusinessTransaction() {
        assertThatThrownBy(() -> markers.tryMarkProcessed(EVENT, NotificationService.CONSUMER_NAME, NOW))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertCounts(0, 0);
    }
    @Test void businessFailureRollsBackMarker() {
        jdbc.execute("ALTER TABLE notifications ADD CONSTRAINT phase15_reject CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(() -> service.create(decoder.decode(json()).orElseThrow()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertCounts(0, 0);
        } finally { jdbc.execute("ALTER TABLE notifications DROP CONSTRAINT phase15_reject"); }
        assertThat(service.create(decoder.decode(json()).orElseThrow())).isEqualTo(ProcessResult.PROCESSED);
        assertCounts(1, 1);
    }
    @Test void markerInsertFailureDoesNotCreateBusinessRow() {
        jdbc.execute("ALTER TABLE processed_events ADD CONSTRAINT phase15_reject CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(() -> service.create(decoder.decode(json()).orElseThrow()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertCounts(0, 0);
        } finally { jdbc.execute("ALTER TABLE processed_events DROP CONSTRAINT phase15_reject"); }
    }
    @Test void outerRollbackRemovesBothWrites() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.create(decoder.decode(json()).orElseThrow());
            assertCounts(1, 1);
            tx.setRollbackOnly();
        });
        assertCounts(0, 0);
    }
    @Test void historicalRowGetsMarkerLazilyWithoutBeingChanged() {
        service.create(decoder.decode(json()).orElseThrow());
        jdbc.update("DELETE FROM processed_events"); // Disposable fixture representing a pre-Phase-15 row.
        var original = jdbc.queryForMap("SELECT * FROM notifications");
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertThat(service.create(decoder.decode(json()).orElseThrow())).isEqualTo(ProcessResult.DUPLICATE);
        assertCounts(1, 1);
        assertThat(jdbc.queryForMap("SELECT * FROM notifications")).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT processed_at FROM processed_events", java.sql.Timestamp.class).toInstant())
                .isEqualTo(NOW.plusSeconds(60));
    }
    @Test void concurrentDuplicateWaitsForWinnerAndSkipsBusinessWrite() throws Exception { concurrent(false); }
    @Test void waitingDuplicateCanProcessAfterFirstTransactionRollsBack() throws Exception { concurrent(true); }

    private void concurrent(boolean rollback) throws Exception {
        var claimed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                    var result = service.create(decoder.decode(json()).orElseThrow());
                    claimed.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                    if (rollback) tx.setRollbackOnly();
                    return result;
                }));
                assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> { secondStarted.countDown(); return service.create(decoder.decode(json()).orElseThrow()); });
                assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(ProcessResult.PROCESSED);
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(rollback ? ProcessResult.PROCESSED : ProcessResult.DUPLICATE);
                assertCounts(1, 1);
            } finally { release.countDown(); }
        }
    }
    private void assertCounts(long business, long processed) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(business);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isEqualTo(processed);
    }
}
