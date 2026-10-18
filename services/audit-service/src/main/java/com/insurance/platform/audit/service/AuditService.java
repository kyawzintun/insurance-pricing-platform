package com.insurance.platform.audit.service;

import com.insurance.platform.audit.dto.AuditMetadata;
import com.insurance.platform.audit.entity.AuditRecord;
import com.insurance.platform.audit.event.QuotePricedEnvelope;
import com.insurance.platform.audit.repository.AuditRecordRepository;
import com.insurance.platform.audit.repository.ProcessedEventRepository;
import com.insurance.platform.audit.event.UserRegisteredEnvelope;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AuditService {
    public static final String CONSUMER_NAME = "audit-service";
    private final ProcessedEventRepository processedEvents;
    private final AuditRecordRepository repository;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public AuditService(AuditRecordRepository repository, Clock clock, ProcessedEventRepository processedEvents) {
        this.repository = repository;
        this.clock = clock;
        this.processedEvents = processedEvents;
    }

    // The local transaction proxy commits before the Kafka listener returns successfully.
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
        Object metadata = expired ? java.util.Map.of("quoteReference", d.quoteReference(), "status", d.status(),
                "coverageType", d.coverageType(), "finalPremium", d.finalPremium(), "currency", d.currency(),
                "originalExpiresAt", d.originalExpiresAt(), "expiredAt", d.expiredAt()) : new AuditMetadata(d.quoteReference(), d.status(), d.coverageType(),
                d.finalPremium(), d.currency(), d.pricedAt(), d.expiresAt());
        repository.saveAndFlush(new AuditRecord(UUID.randomUUID(), event.eventId(), expired ? null : d.customerId().toString(),
                d.quoteId().toString(), event.correlationId(), event.occurredAt().truncatedTo(ChronoUnit.MICROS),
                event.producer(), mapper.writeValueAsString(metadata), now, event.eventType(), expired ? "SYSTEM" : "CUSTOMER"));
        return ProcessResult.PROCESSED;
    }

    @Transactional
    public ProcessResult registerUser(UserRegisteredEnvelope event) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!processedEvents.tryMarkProcessed(event.eventId(), CONSUMER_NAME, now)) return ProcessResult.DUPLICATE;
        if (repository.existsByEventId(event.eventId())) return ProcessResult.DUPLICATE;
        repository.saveAndFlush(new AuditRecord(UUID.randomUUID(), event.eventId(), event.aggregateId().toString(),
                event.aggregateId().toString(), event.correlationId(), event.occurredAt(), event.producer(),
                mapper.writeValueAsString(java.util.Map.of("role", event.data().role(), "status", event.data().status())),
                now, "UserRegistered", "CUSTOMER", "User"));
        return ProcessResult.PROCESSED;
    }
}
