package com.insurance.platform.notification.service;

import com.insurance.platform.notification.entity.Notification;
import com.insurance.platform.notification.event.QuotePricedEnvelope;
import com.insurance.platform.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final NotificationRepository repository;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    // Separate bean: the proxy commits the DB transaction before control returns to the Kafka listener.
    @Transactional
    public void create(QuotePricedEnvelope event) {
        var d = event.data();
        var message = "Your quote " + d.quoteReference() + " is ready. Premium: "
                + d.finalPremium().setScale(2).toPlainString() + " " + d.currency() + ".";

        repository.saveAndFlush(new Notification(UUID.randomUUID(),
                d.customerId(),
                d.quoteId(),
                event.eventId(),
                message,
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }
}
