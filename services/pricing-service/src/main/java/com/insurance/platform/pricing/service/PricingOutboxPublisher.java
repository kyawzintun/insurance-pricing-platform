package com.insurance.platform.pricing.service;

import com.insurance.platform.pricing.repository.OutboxPollingRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true")
public class PricingOutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(PricingOutboxPublisher.class);
    private final OutboxPollingRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final String topic;
    private final int batchSize;

    public PricingOutboxPublisher(
            OutboxPollingRepository repository,
            KafkaTemplate<String, String> kafka,
            Clock clock,
            @Value("${pricing.events.topic:insurance.pricing.events}") String topic,
            @Value("${outbox.publisher.batch-size:50}") int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Outbox batch size must be 1..500");
        }
        this.repository = repository;
        this.kafka = kafka;
        this.clock = clock;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval:2000}", initialDelayString = "${outbox.publisher.poll-interval:2000}")
    public void poll() {
        try {
            var claimed = repository.claim(batchSize);

            if (claimed.isEmpty()) {
                LOG.debug("Outbox poll skipped: another publisher owns the lock");
                return;
            }

            try (var batch = claimed.get()) {
                if (batch.events().isEmpty()) {
                    LOG.debug("Outbox poll: no pending events");
                }

                for (var event : batch.events()) {
                    try {
                        // No database transaction is open. Await the broker acknowledgment before updating status.
                        kafka.send(topic, event.aggregateId(), event.payload()).get();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        LOG.info("Outbox publisher interrupted; pending events remain publishable");
                        return;
                    } catch (Exception failure) {
                        LOG.warn("Outbox publication failed outboxId={} eventId={} aggregateId={} eventType={} topic={} retryCount={} correlationId={} failureType={}",
                                event.id(), event.eventId(), event.aggregateId(), event.eventType(), topic,
                                event.retryCount(), event.correlationId(), failure.getClass().getSimpleName());
                        batch.failed(event.id());
                        // Avoid spending a full batch of network waits when the broker is unavailable.
                        return;
                    }
                    // A failure here leaves PENDING, so a later poll may send the same event again.
                    try {
                        batch.published(event.id(), clock.instant().truncatedTo(ChronoUnit.MICROS));
                    } catch (Exception failure) {
                        LOG.warn("Outbox acknowledgment received but status update failed outboxId={} eventId={} aggregateId={} eventType={} topic={} retryCount={} correlationId={} failureType={}; duplicate publication is possible",
                                event.id(), event.eventId(), event.aggregateId(), event.eventType(), topic,
                                event.retryCount(), event.correlationId(), failure.getClass().getSimpleName());
                        return;
                    }
                    LOG.info("Outbox published outboxId={} eventId={} aggregateId={} eventType={} topic={} retryCount={} correlationId={}",
                            event.id(), event.eventId(), event.aggregateId(), event.eventType(), topic,
                            event.retryCount(), event.correlationId());
                }
            }
        } catch (Exception failure) {
            // No exception text/stack trace: JDBC/Kafka failures may include sensitive payload data.
            LOG.warn("Outbox poll failed failureType={}; pending rows will be polled again", failure.getClass().getSimpleName());
        }
    }
}
