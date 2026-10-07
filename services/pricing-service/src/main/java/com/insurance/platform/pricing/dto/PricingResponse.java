package com.insurance.platform.pricing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PricingResponse(UUID basePricingRuleId, long basePricingRuleVersion,
        BigDecimal basePremium, BigDecimal finalPremium, String currency, Instant calculatedAt,
        List<PricingAdjustment> adjustments) {
    public PricingResponse { adjustments = List.copyOf(adjustments); }
}
