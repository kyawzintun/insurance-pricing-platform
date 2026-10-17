package com.insurance.platform.quote.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QuoteExpiredEventData(
        UUID quoteId,
        String quoteReference,
        UUID customerId,
        String status,
        String coverageType,
        BigDecimal finalPremium,
        String currency,
        Instant expiredAt,
        Instant originalExpiresAt) {}
