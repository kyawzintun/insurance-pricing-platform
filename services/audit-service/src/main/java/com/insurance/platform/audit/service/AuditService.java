package com.insurance.platform.audit.service;

import com.insurance.platform.audit.dto.AuditMetadata;
import com.insurance.platform.audit.entity.AuditRecord;
import com.insurance.platform.audit.event.QuotePricedEnvelope;
import com.insurance.platform.audit.repository.AuditRecordRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AuditService {
    private final AuditRecordRepository repository;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public AuditService(AuditRecordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    // The local transaction proxy commits before the Kafka listener returns successfully.
    @Transactional
    public void create(QuotePricedEnvelope event) {
        var d = event.data();
        var metadata = new AuditMetadata(d.quoteReference(), d.status(), d.coverageType(),
                d.finalPremium(), d.currency(), d.pricedAt(), d.expiresAt());
        repository.saveAndFlush(new AuditRecord(UUID.randomUUID(), event.eventId(), d.customerId().toString(),
                d.quoteId().toString(), event.correlationId(), event.occurredAt().truncatedTo(ChronoUnit.MICROS),
                event.producer(), mapper.writeValueAsString(metadata), clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }
}
