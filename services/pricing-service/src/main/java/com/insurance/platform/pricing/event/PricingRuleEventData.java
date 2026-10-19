package com.insurance.platform.pricing.event;

import com.insurance.platform.pricing.entity.PricingRule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Allowlisted resulting rule state; no authentication material or entity serialization. */
public record PricingRuleEventData(
        UUID pricingRuleId,
        String ruleType,
        String operator,
        String comparisonValue,
        String comparisonValueTo,
        BigDecimal factor,
        BigDecimal fixedAmount,
        Instant effectiveFrom,
        boolean enabled,
        long version,
        UUID changedBy,
        Instant changedAt) {
    public static PricingRuleEventData from(PricingRule rule, UUID admin, Instant now) {
        return new PricingRuleEventData(
                rule.getId(),
                rule.getRuleType().name(),
                rule.getOperator().name(),
                rule.getComparisonValue(),
                rule.getComparisonValueTo(),
                rule.getFactor(),
                rule.getFixedAmount(),
                rule.getEffectiveFrom(),
                rule.isEnabled(),
                rule.getVersion(),
                admin,
                now);
    }
}
