package com.insurance.platform.notification;
import com.insurance.platform.notification.event.UserEventDecoder;
import com.insurance.platform.notification.exception.InvalidUserEventException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class UserEventTests {
    private final UserEventDecoder decoder=new UserEventDecoder();
    private final String json="""
        {"eventId":"6105422e-0262-44b1-8177-82bfc00ec58b","eventType":"UserRegistered","eventVersion":1,
        "aggregateType":"User","aggregateId":"22410825-5c00-4df3-9598-f79833ee28eb","occurredAt":"2026-10-17T00:00:00Z",
        "correlationId":"safe-id","producer":"auth-service","data":{"userId":"22410825-5c00-4df3-9598-f79833ee28eb",
        "email":"customer@example.com","role":"CUSTOMER","status":"ACTIVE","registeredAt":"2026-10-17T00:00:00Z"}}
        """;
    @Test void validUserAndUnknownTypePolicy() {
        assertThat(decoder.decode(json.getBytes(StandardCharsets.UTF_8))).isPresent();
        assertThat(decoder.decode("{\"eventType\":\"FutureEvent\"}".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }
    @Test void invalidSupportedInputsHaveSafePermanentReasons() {
        for(String invalid:java.util.List.of(json.replace("\"eventVersion\":1","\"eventVersion\":2"),json.replace("CUSTOMER","ADMIN"),
                json.replace("customer@example.com"," CUSTOMER@example.com"),json.replace("auth-service","quote-service"),"{broken",json+"{}")) {
            assertThatThrownBy(()->decoder.decode(invalid.getBytes(StandardCharsets.UTF_8))).isInstanceOf(InvalidUserEventException.class);
        }
        assertThatThrownBy(()->decoder.decode(new byte[]{(byte)0xff})).hasMessage("MALFORMED_UTF8");
    }
}
