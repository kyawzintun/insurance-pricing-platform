package com.insurance.platform.quote.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cached samples: no database queries during metric scrapes. -1 means no successful sample yet. */
@Component
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true")
public class OutboxMetrics {
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final AtomicLong pending = new AtomicLong(-1);
    private final AtomicLong oldest = new AtomicLong(-1);
    public OutboxMetrics(ObjectProvider<JdbcTemplate> jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        meters.gauge("insurance.outbox.pending", pending);
        meters.gauge("insurance.outbox.oldest.pending.seconds", oldest);
    }
    @Scheduled(fixedDelayString = "${outbox.metrics.interval:30000}", initialDelayString = "${outbox.metrics.interval:30000}")
    public void sample() {
        try {
            jdbc.getObject().query("SELECT count(*), COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - min(created_at))), 0) FROM outbox_events WHERE status = 'PENDING'", rs -> {
                pending.set(rs.getLong(1));
                oldest.set(Math.max(0, rs.getLong(2)));
            });
        } catch (RuntimeException failure) {
            pending.set(-1); oldest.set(-1);
            LoggerFactory.getLogger(getClass()).warn("action=outbox-metrics result=failure failureType={}", failure.getClass().getSimpleName());
        }
    }
}
