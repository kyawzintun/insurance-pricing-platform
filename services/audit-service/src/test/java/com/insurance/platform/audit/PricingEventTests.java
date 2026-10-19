package com.insurance.platform.audit;
import com.insurance.platform.audit.event.PricingEventDecoder;
import com.insurance.platform.audit.exception.InvalidPricingEventException;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PricingEventTests {
    static String json(UUID eventId,UUID rule,UUID actor,String type,boolean enabled,int version) {
        return """
            {"eventId":"%s","eventType":"%s","eventVersion":1,"aggregateType":"PricingRule","aggregateId":"%s",
            "occurredAt":"2026-10-19T00:00:00Z","correlationId":"phase20-test","producer":"pricing-service",
            "data":{"pricingRuleId":"%s","ruleType":"DRIVER_AGE","operator":"LESS_THAN","comparisonValue":"21",
            "comparisonValueTo":null,"factor":1.3000,"fixedAmount":null,"effectiveFrom":"2026-11-01T00:00:00Z",
            "enabled":%s,"version":%s,"changedBy":"%s","changedAt":"2026-10-19T00:00:00Z"}}
            """.formatted(eventId,type,rule,rule,enabled,version,actor);
    }
    @Test void supportedTypesAndUnknownSkip() {
        var decoder=new PricingEventDecoder();
        for(String type:java.util.List.of("PricingRuleCreated","PricingRuleUpdated","PricingRuleEnabled","PricingRuleDisabled"))
            assertThat(decoder.decode(json(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),type,!type.equals("PricingRuleDisabled"),1).getBytes(StandardCharsets.UTF_8))).isPresent();
        assertThat(decoder.decode("{\"eventType\":\"FutureEvent\"}".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }
    @Test void supportedInvalidInputFailsPermanentlyWithoutPayloadBearingCauses() {
        var decoder=new PricingEventDecoder();
        String valid=json(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"PricingRuleCreated",true,0);
        for(String invalid:java.util.List.of(valid.replace("\"eventVersion\":1","\"eventVersion\":2"),valid.replace("pricing-service","auth-service"),
                valid.replace("1.3000","\"1.3\""),valid.replace("LESS_THAN","BOGUS"),valid.replace("21","unbounded"),valid+"{}","{broken"))
            assertThatThrownBy(()->decoder.decode(invalid.getBytes(StandardCharsets.UTF_8))).isInstanceOf(InvalidPricingEventException.class).hasNoCause();
    }
}
