package com.insurance.platform.quote.event;

import com.insurance.platform.quote.enums.CoverageType;
import com.insurance.platform.quote.enums.QuoteStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QuotePricedEventData(
        UUID quoteId,
        String quoteReference,
        UUID customerId,
        QuoteStatus status,
        CoverageType coverageType,
        BigDecimal finalPremium,
        String currency,
        Instant pricedAt,
        Instant expiresAt) {}
