package com.insurance.platform.quote.dto;

import com.insurance.platform.quote.enums.CoverageType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateQuoteRequest(
        @NotNull LocalDate dateOfBirth,
        @NotNull @Min(0) Integer drivingExperienceYears,
        @NotNull @Min(0) Integer previousClaimsCount,
        @NotNull UUID vehicleBrandId,
        @NotNull UUID vehicleModelId,
        @NotNull @Min(1886) Integer vehicleManufacturingYear,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 2) BigDecimal vehicleValue,
        @NotNull @Min(1) Integer engineSizeCc,
        @NotNull CoverageType coverageType) {}
