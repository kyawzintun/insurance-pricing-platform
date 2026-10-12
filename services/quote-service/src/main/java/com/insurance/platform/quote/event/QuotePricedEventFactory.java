package com.insurance.platform.quote.event;

import com.insurance.platform.quote.dto.QuoteResponse;
import com.insurance.platform.quote.enums.QuoteStatus;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class QuotePricedEventFactory {
    private final Clock clock;
    private final QuoteCorrelationId correlation;

    public QuotePricedEventFactory(Clock clock, QuoteCorrelationId correlation) {
        this.clock = clock;
        this.correlation = correlation;
    }

    public QuotePricedEvent create(UUID customerId, QuoteResponse quote) {
        if (quote.status() != QuoteStatus.PRICED || quote.pricing() == null) {
            throw new IllegalArgumentException("QuotePriced requires a priced snapshot");
        }
        var data = new QuotePricedEventData(quote.id(), quote.quoteReference(), customerId,
                quote.status(), quote.coverageType(), quote.pricing().finalPremium(), quote.pricing().currency(),
                quote.pricedAt(), quote.expiresAt());

        return new QuotePricedEvent(new EventEnvelope<>(
                UUID.randomUUID(),
                QuotePricedEvent.TYPE,
                QuotePricedEvent.VERSION,
                QuotePricedEvent.AGGREGATE_TYPE,
                quote.id(),
                clock.instant().truncatedTo(ChronoUnit.MICROS),
                correlation.current(),
                QuotePricedEvent.PRODUCER,
                data));
    }
}
