package com.insurance.platform.notification.event;
import com.insurance.platform.notification.exception.InvalidUserEventException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
@Component
public class UserEventDecoder {
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public Optional<UserRegisteredEnvelope> decode(byte[] bytes) {
        require(bytes != null, "INVALID_ENVELOPE");
        String json;
        try { json = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException failure) { throw new InvalidUserEventException("MALFORMED_UTF8"); }
        tools.jackson.databind.JsonNode root;
        try { root = mapper.readTree(json); } catch (RuntimeException failure) { throw new InvalidUserEventException("MALFORMED_JSON"); }
        require(root != null && root.isObject(), "INVALID_ENVELOPE");
        require(root.path("eventType").isString() && !root.path("eventType").asText().isBlank(), "INVALID_EVENT_TYPE");
        if (!"UserRegistered".equals(root.path("eventType").asText())) return Optional.empty();
        require(root.path("eventVersion").isIntegralNumber() && root.path("eventVersion").bigIntegerValue().equals(java.math.BigInteger.ONE), "UNSUPPORTED_EVENT_VERSION");
        UserRegisteredEnvelope event;
        try { event = mapper.treeToValue(root, UserRegisteredEnvelope.class); }
        catch (RuntimeException failure) { throw new InvalidUserEventException("INVALID_DATA"); }
        require(event.eventId() != null && event.aggregateId() != null && event.occurredAt() != null
                && "User".equals(event.aggregateType()) && "auth-service".equals(event.producer()), "INVALID_ENVELOPE");
        require(event.correlationId() != null && event.correlationId().matches("[A-Za-z0-9._-]{1,128}"), "INVALID_CORRELATION_ID");
        var d = event.data();
        require(d != null && event.aggregateId().equals(d.userId()) && "CUSTOMER".equals(d.role())
                && "ACTIVE".equals(d.status()) && event.occurredAt().equals(d.registeredAt())
                && d.email() != null && d.email().length() <= 255
                && d.email().matches("[^\\s@]+@[^\\s@]+")
                && d.email().equals(d.email().strip().toLowerCase(java.util.Locale.ROOT)), "INVALID_DATA");
        return Optional.of(event);
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new InvalidUserEventException(reason); }
}
