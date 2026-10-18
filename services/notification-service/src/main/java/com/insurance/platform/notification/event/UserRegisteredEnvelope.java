package com.insurance.platform.notification.event;
import java.time.Instant;
import java.util.UUID;
public record UserRegisteredEnvelope(
        UUID eventId,
        String eventType,
        Integer eventVersion,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        String correlationId,
        String producer,
        Data data) {
    public record Data(UUID userId, String email, String role, String status, Instant registeredAt) {}
}
