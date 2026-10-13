package com.insurance.platform.notification.event;

import com.insurance.platform.notification.exception.InvalidQuoteEventException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class QuoteEventDecoder {
    // Fixed DTO mapping; Kafka Java type headers and polymorphic default typing are not used.
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public Optional<QuotePricedEnvelope> decode(String json) {
        JsonNode root;

        try {
            root = mapper.readTree(json);
        } catch (RuntimeException failure) {
            throw invalid("MALFORMED_JSON");
        }

        require(root != null && root.isObject(), "INVALID_ENVELOPE");
        require(root.path("eventType").isString() && !root.path("eventType").asText().isBlank(), "INVALID_EVENT_TYPE");

        if (!"QuotePriced".equals(root.path("eventType").asText())) return Optional.empty();

        require(root.path("eventVersion").isIntegralNumber() && root.path("eventVersion").bigIntegerValue().equals(java.math.BigInteger.ONE),
                "UNSUPPORTED_EVENT_VERSION");

        var data = root.path("data");
        require(data.isObject() && data.path("finalPremium").isNumber(), "INVALID_DATA");

        QuotePricedEnvelope event;
        try {
            event = mapper.treeToValue(root, QuotePricedEnvelope.class);
        } catch (RuntimeException failure) {
            throw invalid("INVALID_DATA");
        }

        require(event.eventId() != null && event.aggregateId() != null && event.occurredAt() != null,
                "INVALID_ENVELOPE");
        require("Quote".equals(event.aggregateType()) && "quote-service".equals(event.producer()), "INVALID_ENVELOPE");
        require(event.correlationId() != null && event.correlationId().matches("[A-Za-z0-9._-]{1,128}"), "INVALID_CORRELATION_ID");

        var d = event.data();
        require(d.quoteId() != null && d.customerId() != null && event.aggregateId().equals(d.quoteId()), "INVALID_QUOTE_ID");
        require(d.quoteReference() != null && d.quoteReference().matches("[A-Za-z0-9-]{1,50}"), "INVALID_REFERENCE");
        require("PRICED".equals(d.status()) && ("COMPREHENSIVE".equals(d.coverageType()) || "THIRD_PARTY".equals(d.coverageType())), "INVALID_DATA");
        require(d.finalPremium() != null && d.finalPremium().signum() >= 0 && d.finalPremium().scale() <= 2
                && (long) d.finalPremium().precision() - d.finalPremium().scale() <= 13, "INVALID_PREMIUM");
        require("THB".equals(d.currency()) && d.pricedAt() != null && d.expiresAt() != null
                && d.expiresAt().isAfter(d.pricedAt()), "INVALID_DATA");

        return Optional.of(event);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw invalid(reason);
    }

    private static InvalidQuoteEventException invalid(String reason) {
        return new InvalidQuoteEventException(reason);
    }
}
