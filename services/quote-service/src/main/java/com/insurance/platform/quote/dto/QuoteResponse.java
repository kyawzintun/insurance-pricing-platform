package com.insurance.platform.quote.dto;

import com.insurance.platform.quote.entity.Quote;
import com.insurance.platform.quote.enums.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public record QuoteResponse(UUID id, String quoteReference, QuoteStatus status, CoverageType coverageType,
        Driver driver, VehicleSnapshot vehicle, Pricing pricing, Instant createdAt, Instant pricedAt, Instant expiresAt) {
    public record Driver(LocalDate dateOfBirth, int drivingExperienceYears, int previousClaimsCount) {}
    public record Adjustment(UUID pricingRuleId, PricingRuleType ruleType, String description, String inputValue,
                             BigDecimal factor, BigDecimal amountBefore, BigDecimal amountAfter, int sequenceNumber) {}
    public record Pricing(BigDecimal basePremium, BigDecimal finalPremium, String currency, Instant calculatedAt,
                          List<Adjustment> adjustments) {
        public Pricing { adjustments = List.copyOf(adjustments); }
    }
    public static QuoteResponse from(Quote quote) {
        var d = quote.getDriver(); var v = quote.getVehicle(); var p = quote.getPricing();
        return new QuoteResponse(quote.getId(), quote.getQuoteReference(), quote.getStatus(), quote.getCoverageType(),
                new Driver(d.getDateOfBirth(), d.getDrivingExperienceYears(), d.getPreviousClaimsCount()),
                new VehicleSnapshot(v.getBrandId(), v.getBrandName(), v.getModelId(), v.getModelName(),
                        v.getManufacturingYear(), v.getVehicleValue(), v.getEngineSizeCc()),
                new Pricing(p.getBasePremium(), p.getFinalPremium(), p.getCurrency(), p.getCalculatedAt(),
                        p.getAdjustments().stream().map(a -> new Adjustment(a.getPricingRuleId(), a.getRuleType(),
                                a.getDescription(), a.getInputValue(), a.getFactor(), a.getAmountBefore(),
                                a.getAmountAfter(), a.getSequenceNumber())).toList()),
                quote.getCreatedAt(), quote.getPricedAt(), quote.getExpiresAt());
    }
}
