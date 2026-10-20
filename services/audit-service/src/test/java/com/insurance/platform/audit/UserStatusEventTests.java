package com.insurance.platform.audit;
import com.insurance.platform.audit.event.UserStatusEventDecoder;
import com.insurance.platform.audit.exception.InvalidUserEventException;
import java.time.Instant;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class UserStatusEventTests {
    static String json(UUID event,UUID user,UUID actor,String type,Instant now) {
        return """
            {"eventId":"%s","eventType":"%s","eventVersion":1,"aggregateType":"User","aggregateId":"%s",
             "occurredAt":"%s","correlationId":"phase21-test","producer":"auth-service",
             "data":{"userId":"%s","email":"customer@example.com","role":"CUSTOMER","status":"%s",
             "changedBy":"%s","changedAt":"%s"}}
            """.formatted(event,type,user,now,user,type.equals("UserDisabled")?"DISABLED":"ACTIVE",actor,now);
    }
    @Test void statusTypesValidateAndUnknownTypesSkip() {
        var decoder=new UserStatusEventDecoder();
        for(String type:java.util.List.of("UserDisabled","UserEnabled"))
            assertThat(decoder.decode(json(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),type,Instant.now()).getBytes(StandardCharsets.UTF_8))).isPresent();
        assertThat(decoder.decode("{\"eventType\":\"FutureEvent\"}".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }
    @Test void invalidSupportedStatusEventsFailPermanentlyWithSafeReasons() {
        var decoder=new UserStatusEventDecoder();String valid=json(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"UserDisabled",Instant.now());
        for(String invalid:java.util.List.of(valid.replace("\"eventVersion\":1","\"eventVersion\":2"),valid.replace("DISABLED","ACTIVE"),
                valid.replace("CUSTOMER","ADMIN"),valid.replace("changedBy","untrustedActor"),valid.replace("auth-service","pricing-service"),valid+"{}","{broken"))
            assertThatThrownBy(()->decoder.decode(invalid.getBytes(StandardCharsets.UTF_8))).isInstanceOf(InvalidUserEventException.class).hasNoCause();
    }
}
