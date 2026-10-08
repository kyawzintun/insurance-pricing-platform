package com.insurance.platform.pricing.dto;

import com.insurance.platform.pricing.enums.RuleType;
import com.insurance.platform.pricing.enums.RuleOperator;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

public record UpdatePricingRuleRequest(
        @NotNull RuleType ruleType,
        @NotNull RuleOperator operator,
        @Size(max = 255) String comparisonValue,
        @Size(max = 255) String comparisonValueTo,
        @DecimalMin("0") @Digits(integer = 6, fraction = 4) BigDecimal factor,
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 2) BigDecimal fixedAmount,
        @NotNull Instant effectiveFrom,
        @NotNull Boolean enabled,
        @Size(max = 255) String description,
        @NotNull @Min(0) Long version) {}
