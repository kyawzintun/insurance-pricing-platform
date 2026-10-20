package com.insurance.platform.audit.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer-owned status contract, keeping UserRegistered v1 unchanged.
 */
public record UserStatusEnvelope(
        UUID eventId,
        String eventType,
        Integer eventVersion,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        String correlationId,
        String producer,
        Data data) {
    public record Data(
            UUID userId,
            String email,
            String role,
            String status,
            UUID changedBy,
            Instant changedAt) {
    }
}
