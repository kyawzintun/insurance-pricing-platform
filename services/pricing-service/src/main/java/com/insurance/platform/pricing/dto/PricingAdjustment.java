package com.insurance.platform.pricing.dto;

import com.insurance.platform.pricing.enums.RuleType;
import java.math.BigDecimal;
import java.util.UUID;

public record PricingAdjustment(UUID pricingRuleId, long pricingRuleVersion, RuleType ruleType,
        String description, String inputValue, BigDecimal factor, BigDecimal amountBefore,
        BigDecimal amountAfter, int sequenceNumber) {}
