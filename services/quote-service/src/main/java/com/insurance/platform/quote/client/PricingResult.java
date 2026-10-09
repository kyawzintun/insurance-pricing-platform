package com.insurance.platform.quote.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.insurance.platform.quote.enums.PricingRuleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Pricing's wire contract, independent of Quote's persisted snapshot DTO. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingResult(@NotNull UUID basePricingRuleId, @NotNull @Min(0) Long basePricingRuleVersion,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 2) BigDecimal basePremium,
        @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 2) BigDecimal finalPremium,
        @NotNull @Pattern(regexp = "THB") String currency, @NotNull Instant calculatedAt,
        @NotNull @Size(max = 6) List<@NotNull @Valid Adjustment> adjustments) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Adjustment(@NotNull UUID pricingRuleId, @NotNull @Min(0) Long pricingRuleVersion,
            @NotNull PricingRuleType ruleType, @Size(max = 255) String description,
            @NotNull @Size(max = 255) String inputValue,
            @NotNull @DecimalMin("0") @Digits(integer = 6, fraction = 4) BigDecimal factor,
            @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 2) BigDecimal amountBefore,
            @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 2) BigDecimal amountAfter,
            @NotNull @Min(1) Integer sequenceNumber) {}
}
