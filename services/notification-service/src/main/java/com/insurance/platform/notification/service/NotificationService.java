package com.insurance.platform.notification.service;

import com.insurance.platform.notification.entity.Notification;
import com.insurance.platform.notification.event.QuotePricedEnvelope;
import com.insurance.platform.notification.repository.NotificationRepository;
import com.insurance.platform.notification.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    public static final String CONSUMER_NAME = "notification-service";
    private final ProcessedEventRepository processedEvents;
    private final NotificationRepository repository;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, Clock clock, ProcessedEventRepository processedEvents) {
        this.repository = repository;
        this.clock = clock;
        this.processedEvents = processedEvents;
    }

    // Separate bean: the proxy commits the DB transaction before control returns to the Kafka listener.
    @Transactional
    public ProcessResult create(QuotePricedEnvelope event) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!processedEvents.tryMarkProcessed(event.eventId(), CONSUMER_NAME, now)) {
            return ProcessResult.DUPLICATE;
        }
        // Lazy compatibility for pre-Phase-15 rows. The atomic marker claim is the concurrency guard.
        // Do not catch unique violations in an aborted PostgreSQL transaction.
        if (repository.existsByEventId(event.eventId())) {
            return ProcessResult.DUPLICATE;
        }
        var d = event.data();
        var expired = "QuoteExpired".equals(event.eventType());
        var message = expired ? "Your quote " + d.quoteReference() + " has expired." : "Your quote " + d.quoteReference() + " is ready. Premium: "
                + d.finalPremium().setScale(2).toPlainString() + " " + d.currency() + ".";

        repository.saveAndFlush(new Notification(UUID.randomUUID(),
                d.customerId(),
                d.quoteId(),
                event.eventId(),
                message,
                now, expired ? "QUOTE_EXPIRED" : "QUOTE_PRICED"));
        return ProcessResult.PROCESSED;
    }
}
