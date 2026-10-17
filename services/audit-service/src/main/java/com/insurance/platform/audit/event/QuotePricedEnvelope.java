package com.insurance.platform.audit.event;

import java.time.Instant;
import java.util.UUID;

/** Consumer-owned envelope for QuotePriced and QuoteExpired v1; no producer dependency. */
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
