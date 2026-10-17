package com.insurance.platform.quote.service;

import com.insurance.platform.quote.entity.OutboxEvent;
import com.insurance.platform.quote.event.*;
import com.insurance.platform.quote.repository.OutboxRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QuoteExpirationService {
    private static final Logger LOG = LoggerFactory.getLogger(QuoteExpirationService.class);
    private final JdbcTemplate jdbc;
    private final OutboxRepository outbox;
    private final JsonMapper json;
    private final Clock clock;

    public QuoteExpirationService(JdbcTemplate jdbc, OutboxRepository outbox, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    /** Bounded transaction: row locks remain held until status and every event intent commit. */
    @Transactional
    public int expireBatch(int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Expiration batch size must be 1..500");
        }

        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var due = jdbc.query("""
                SELECT id, quote_reference, customer_id, coverage_type, premium_amount, currency, expires_at
                FROM quotes WHERE status = 'PRICED' AND expires_at <= ?
                ORDER BY expires_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                """,
                (rs, row) ->
                        new QuoteExpiredEventData(
                                rs.getObject("id", UUID.class),
                                rs.getString("quote_reference"),
                                rs.getObject("customer_id", UUID.class),
                                "EXPIRED",
                                rs.getString("coverage_type"),
                                rs.getBigDecimal("premium_amount"),
                                rs.getString("currency"),
                                now,
                                rs.getTimestamp("expires_at").toInstant()
                        ),
                Timestamp.from(now),
                batchSize);

        for (var data : due) {
            if (jdbc.update("UPDATE quotes SET status='EXPIRED', updated_at=? WHERE id=? AND status='PRICED'",
                    Timestamp.from(now), data.quoteId()) != 1) {
                throw new IllegalStateException("Locked quote transition failed");
            }

            var event = new EventEnvelope<>(
                    UUID.randomUUID(),
                    "QuoteExpired",
                    1,
                    "Quote",
                    data.quoteId(),
                    now,
                    UUID.randomUUID().toString(),
                    "quote-service",
                    data
            );

            outbox.saveAndFlush(new OutboxEvent(event, json.writeValueAsString(event)));

            LOG.debug("Quote expiration staged quoteId={} oldStatus=PRICED newStatus=EXPIRED eventId={} correlationId={}",
                    data.quoteId(), event.eventId(), event.correlationId());
        }

        if (!due.isEmpty()) {
            LOG.info("Quote expiration staged claimed={} batchSize={}", due.size(), batchSize);
        }

        return due.size();
    }
}
