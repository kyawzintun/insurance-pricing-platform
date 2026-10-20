package com.insurance.platform.audit.event;

import com.insurance.platform.audit.exception.InvalidUserEventException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class UserStatusEventDecoder {
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public Optional<UserStatusEnvelope> decode(byte[] bytes) {
        require(bytes != null, "INVALID_ENVELOPE");
        String json;

        try {
            json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new InvalidUserEventException("MALFORMED_UTF8");
        }

        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (RuntimeException failure) {
            throw new InvalidUserEventException("MALFORMED_JSON");
        }

        require(root != null && root.isObject(), "INVALID_ENVELOPE");
        require(root.path("eventType").isString() && !root.path("eventType").asText().isBlank(), "INVALID_EVENT_TYPE");

        if (!Set.of("UserDisabled", "UserEnabled").contains(root.path("eventType").asText())) {
            return Optional.empty();
        }

        require(root.path("eventVersion").isIntegralNumber() && root.path("eventVersion").bigIntegerValue().equals(BigInteger.ONE), "UNSUPPORTED_EVENT_VERSION");
        UserStatusEnvelope event;

        try {
            event = mapper.treeToValue(root, UserStatusEnvelope.class);
        } catch (RuntimeException failure) {
            throw new InvalidUserEventException("INVALID_DATA");
        }

        require(event.eventId() != null && event.aggregateId() != null && event.occurredAt() != null
                && "User".equals(event.aggregateType()) && "auth-service".equals(event.producer()), "INVALID_ENVELOPE");
        require(event.correlationId() != null && event.correlationId().matches("[A-Za-z0-9._-]{1,128}"), "INVALID_CORRELATION_ID");
        
        var d = event.data();
        var expected = "UserDisabled".equals(event.eventType()) ? "DISABLED" : "ACTIVE";
        require(d != null && event.aggregateId().equals(d.userId()) && "CUSTOMER".equals(d.role())
                && expected.equals(d.status()) && d.changedBy() != null && event.occurredAt().equals(d.changedAt())
                && d.email() != null && d.email().length() <= 255
                && d.email().matches("[^\\s@]+@[^\\s@]+")
                && d.email().equals(d.email().strip().toLowerCase(Locale.ROOT)), "INVALID_DATA");
        return Optional.of(event);
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new InvalidUserEventException(reason);
    }
}
