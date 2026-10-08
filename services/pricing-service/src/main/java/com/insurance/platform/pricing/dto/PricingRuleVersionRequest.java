package com.insurance.platform.pricing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PricingRuleVersionRequest(@NotNull @Min(0) Long version) {}
