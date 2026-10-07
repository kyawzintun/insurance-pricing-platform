package com.insurance.platform.pricing.dto;

import com.insurance.platform.pricing.enums.CoverageType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PricingRequest(
        @NotNull LocalDate dateOfBirth,
        @NotNull @Min(0) Integer drivingExperienceYears,
        @NotNull @Min(1886) Integer vehicleManufacturingYear,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 2) BigDecimal vehicleValue,
        @NotNull @Min(1) Integer engineSizeCc,
        @NotNull @Min(0) Integer previousClaimsCount,
        @NotNull CoverageType coverageType) {}
