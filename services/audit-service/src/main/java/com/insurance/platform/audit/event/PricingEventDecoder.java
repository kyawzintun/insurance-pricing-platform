package com.insurance.platform.audit.event;

import com.insurance.platform.audit.exception.InvalidPricingEventException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

@Component
public class PricingEventDecoder {
    private static final Set<String> TYPES = Set.of("PricingRuleCreated", "PricingRuleUpdated", "PricingRuleEnabled", "PricingRuleDisabled");
    private static final Set<String> RULES = Set.of("BASE_PREMIUM", "DRIVER_AGE", "DRIVING_EXPERIENCE", "VEHICLE_AGE", "VEHICLE_VALUE", "PREVIOUS_CLAIMS", "COVERAGE_TYPE");
    private static final Set<String> OPERATORS = Set.of("EQUALS", "LESS_THAN", "LESS_THAN_OR_EQUAL", "GREATER_THAN", "GREATER_THAN_OR_EQUAL", "BETWEEN");
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();

    public Optional<PricingRuleEnvelope> decode(byte[] bytes) {
        require(bytes != null, "INVALID_ENVELOPE");
        String json;

        try {
            json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw invalid("MALFORMED_UTF8");
        }

        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (RuntimeException failure) {
            throw invalid("MALFORMED_JSON");
        }

        require(root != null && root.isObject(), "INVALID_ENVELOPE");
        require(root.path("eventType").isString() && !root.path("eventType").asText().isBlank(), "INVALID_EVENT_TYPE");

        if (!TYPES.contains(root.path("eventType").asText())) {
            return Optional.empty();
        }

        require(root.path("eventVersion").isIntegralNumber() && root.path("eventVersion").bigIntegerValue().equals(BigInteger.ONE), "UNSUPPORTED_EVENT_VERSION");

        var data = root.path("data");
        require(data.isObject() && data.path("enabled").isBoolean() && data.path("version").isIntegralNumber()
                && (data.path("factor").isNull() || data.path("factor").isNumber())
                && (data.path("fixedAmount").isNull() || data.path("fixedAmount").isNumber()), "INVALID_DATA");

        PricingRuleEnvelope event;
        try {
            event = mapper.treeToValue(root, PricingRuleEnvelope.class);
        } catch (RuntimeException failure) {
            throw invalid("INVALID_DATA");
        }

        require(event.eventId() != null && event.aggregateId() != null && event.occurredAt() != null
                && "PricingRule".equals(event.aggregateType()) && "pricing-service".equals(event.producer()), "INVALID_ENVELOPE");
        require(event.correlationId() != null && event.correlationId().matches("[A-Za-z0-9._-]{1,128}"), "INVALID_CORRELATION_ID");

        var d = event.data();
        require(d != null && event.aggregateId().equals(d.pricingRuleId()) && d.changedBy() != null
                && event.occurredAt().equals(d.changedAt()) && d.effectiveFrom() != null && d.version() != null && d.version() >= 0
                && d.ruleType() != null && RULES.contains(d.ruleType()) && d.operator() != null && OPERATORS.contains(d.operator()), "INVALID_DATA");
        require(!"PricingRuleEnabled".equals(event.eventType()) || Boolean.TRUE.equals(d.enabled()), "INVALID_DATA");
        require(!"PricingRuleDisabled".equals(event.eventType()) || Boolean.FALSE.equals(d.enabled()), "INVALID_DATA");

        if ("BASE_PREMIUM".equals(d.ruleType())) {
            require("EQUALS".equals(d.operator()) && d.comparisonValue() == null && d.comparisonValueTo() == null && d.factor() == null
                    && d.fixedAmount() != null && d.fixedAmount().signum() > 0 && fits(d.fixedAmount(), 13, 2), "INVALID_DATA");
        } else {
            require(d.fixedAmount() == null && d.factor() != null && d.factor().signum() >= 0 && fits(d.factor(), 6, 4)
                    && d.comparisonValue() != null && d.comparisonValue().length() <= 100, "INVALID_DATA");
            if ("COVERAGE_TYPE".equals(d.ruleType())) {
                require("EQUALS".equals(d.operator()) && Set.of("COMPREHENSIVE", "THIRD_PARTY").contains(d.comparisonValue()) && d.comparisonValueTo() == null, "INVALID_DATA");
            } else {
                require(number(d.comparisonValue()), "INVALID_DATA");
                if ("BETWEEN".equals(d.operator())) {
                    require(number(d.comparisonValueTo()) && new BigDecimal(d.comparisonValue()).compareTo(new BigDecimal(d.comparisonValueTo())) <= 0, "INVALID_DATA");
                } else require(d.comparisonValueTo() == null, "INVALID_DATA");
            }
        }
        return Optional.of(event);
    }

    private static boolean fits(BigDecimal n, int integers, int scale) {
        return n.scale() <= scale && (long) n.precision() - n.scale() <= integers;
    }

    private static boolean number(String n) {
        return n != null && n.matches("[+-]?[0-9]{1,15}(?:\\.[0-9]{1,4})?");
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw invalid(reason);
    }

    private static InvalidPricingEventException invalid(String reason) {
        return new InvalidPricingEventException(reason);
    }
}
