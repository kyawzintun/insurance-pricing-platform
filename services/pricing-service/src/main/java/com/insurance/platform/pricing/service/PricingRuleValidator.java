package com.insurance.platform.pricing.service;

import com.insurance.platform.pricing.entity.PricingRule;
import com.insurance.platform.pricing.enums.CoverageType;
import com.insurance.platform.pricing.enums.RuleOperator;
import com.insurance.platform.pricing.enums.RuleType;
import com.insurance.platform.pricing.exception.PricingConfigurationException;
import java.math.BigDecimal;

/** Explicit rule semantics shared by calculation and administration. */
public final class PricingRuleValidator {
    private PricingRuleValidator() {}

    public static void validateRule(PricingRule rule) {
        if (rule.getId() == null || rule.getRuleType() == null || rule.getOperator() == null) {
            throw new PricingConfigurationException();
        }
        if (rule.getRuleType() == RuleType.BASE_PREMIUM) {
            if (rule.getOperator() != RuleOperator.EQUALS || rule.getFixedAmount() == null
                    || rule.getFixedAmount().signum() <= 0 || rule.getFactor() != null
                    || rule.getComparisonValue() != null || rule.getComparisonValueTo() != null) {
                throw new PricingConfigurationException();
            }
            if (rule.getFixedAmount().compareTo(new BigDecimal("9999999999999.99")) > 0) {
                throw new PricingConfigurationException();
            }
            return;
        }
        if (rule.getFactor() == null || rule.getFactor().signum() < 0 || rule.getFixedAmount() != null) {
            throw new PricingConfigurationException();
        }
        if (rule.getRuleType() == RuleType.COVERAGE_TYPE) {
            if (rule.getOperator() != RuleOperator.EQUALS || rule.getComparisonValueTo() != null) {
                throw new PricingConfigurationException();
            }
            try {
                CoverageType.valueOf(rule.getComparisonValue());
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new PricingConfigurationException();
            }
        } else {
            var lower = numeric(rule.getComparisonValue());
            if (rule.getOperator() == RuleOperator.BETWEEN) {
                if (lower.compareTo(numeric(rule.getComparisonValueTo())) > 0) {
                    throw new PricingConfigurationException();
                }
            } else if (rule.getComparisonValueTo() != null) {
                throw new PricingConfigurationException();
            }
        }
    }

    public static BigDecimal numeric(String value) {
        // Plain decimals only; bound parsing and reject scientific notation/extreme exponents.
        if (value == null || !value.matches("[+-]?[0-9]{1,15}(\\.[0-9]{1,4})?")) {
            throw new PricingConfigurationException();
        }
        return new BigDecimal(value);
    }

}
