package com.insurance.platform.notification.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QuotePricedData(
        UUID quoteId,
        String quoteReference,
        UUID customerId,
        String status,
        String coverageType,
        BigDecimal finalPremium,
        String currency,
        Instant pricedAt,
        Instant expiresAt,
        Instant expiredAt,
        Instant originalExpiresAt) {
    public QuotePricedData(
            UUID quoteId,
            String quoteReference,
            UUID customerId,
            String status,
            String coverageType,
            BigDecimal finalPremium,
            String currency,
            Instant pricedAt,
            Instant expiresAt) {
        this(
                quoteId,
                quoteReference,
                customerId,
                status,
                coverageType,
                finalPremium,
                currency,
                pricedAt,
                expiresAt,
                null,
                null
        );
    }
}
