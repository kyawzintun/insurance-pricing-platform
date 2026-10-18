package com.insurance.platform.auth.event;

import java.time.Instant;
import java.util.UUID;

/** Versioned wire envelope. No JPA entities or authentication credentials belong here. */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        String correlationId,
        String producer,
        T data) {}
