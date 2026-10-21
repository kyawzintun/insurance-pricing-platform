package com.insurance.platform.audit.dto;

import com.insurance.platform.audit.entity.AuditRecord;

import java.time.Instant;
import java.util.UUID;

public record AuditRow(
        UUID id,
        UUID eventId,
        String eventType,
        String actorType,
        String actorId,
        String entityType,
        String entityId,
        String correlationId,
        String producer,
        Instant occurredAt,
        Instant createdAt) {
    public static AuditRow from(AuditRecord r) {
        return new AuditRow(
                r.getId(),
                r.getEventId(),
                r.getEventType(),
                r.getActorType(),
                r.getActorId(),
                r.getEntityType(),
                r.getEntityId(),
                r.getCorrelationId(),
                r.getProducer(),
                r.getOccurredAt(),
                r.getCreatedAt());
    }
}
