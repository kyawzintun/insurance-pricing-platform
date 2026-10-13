package com.insurance.platform.notification.event;

import java.time.Instant;
import java.util.UUID;

/** Consumer-owned DTO matching the Phase 12 wire contract; no dependency on producer code. */
public record QuotePricedEnvelope(
        UUID eventId,
        String eventType,
        Integer eventVersion,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        String correlationId,
        String producer,
        QuotePricedData data) {}
