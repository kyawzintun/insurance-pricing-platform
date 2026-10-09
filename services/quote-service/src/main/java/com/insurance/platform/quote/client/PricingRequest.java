package com.insurance.platform.quote.client;

import com.insurance.platform.quote.dto.CreateQuoteRequest;
import com.insurance.platform.quote.enums.CoverageType;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PricingRequest(LocalDate dateOfBirth, int drivingExperienceYears, int vehicleManufacturingYear,
        BigDecimal vehicleValue, int engineSizeCc, int previousClaimsCount, CoverageType coverageType) {
    public static PricingRequest from(CreateQuoteRequest request) {
        return new PricingRequest(request.dateOfBirth(), request.drivingExperienceYears(), request.vehicleManufacturingYear(),
                request.vehicleValue(), request.engineSizeCc(), request.previousClaimsCount(), request.coverageType());
    }
}
