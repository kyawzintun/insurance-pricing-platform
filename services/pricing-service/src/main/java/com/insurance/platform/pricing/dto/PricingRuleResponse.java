package com.insurance.platform.pricing.dto;

import com.insurance.platform.pricing.entity.PricingRule;
import com.insurance.platform.pricing.enums.RuleType;
import com.insurance.platform.pricing.enums.RuleOperator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PricingRuleResponse(UUID id, RuleType ruleType, RuleOperator operator,
        String comparisonValue, String comparisonValueTo, BigDecimal factor, BigDecimal fixedAmount,
        Instant effectiveFrom, boolean enabled, String description, long version,
        Instant createdAt, Instant updatedAt) {
    public static PricingRuleResponse from(PricingRule rule) {
        return new PricingRuleResponse(rule.getId(), rule.getRuleType(), rule.getOperator(),
                rule.getComparisonValue(), rule.getComparisonValueTo(), rule.getFactor(), rule.getFixedAmount(),
                rule.getEffectiveFrom(), rule.isEnabled(), rule.getDescription(), rule.getVersion(),
                rule.getCreatedAt(), rule.getUpdatedAt());
    }
}
