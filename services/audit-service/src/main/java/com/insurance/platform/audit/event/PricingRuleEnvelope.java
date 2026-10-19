package com.insurance.platform.audit.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Consumer-owned v1 contract; independent of Pricing's classes. */
public record PricingRuleEnvelope(
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
            UUID pricingRuleId,
            String ruleType,
            String operator,
            String comparisonValue,
            String comparisonValueTo,
            BigDecimal factor,
            BigDecimal fixedAmount,
            Instant effectiveFrom,
            Boolean enabled,
            Long version,
            UUID changedBy,
            Instant changedAt) {}
}
