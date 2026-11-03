package com.insurance.platform.notification;

import com.insurance.platform.notification.event.EventObservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;

class EventObservationTests {
    @Test void countsOutcomesAndRestoresCorrelationEvenOnFailure() {
        var meters = new SimpleMeterRegistry();
        var observation = new EventObservation(meters);
        MDC.put("correlationId", "previous");
        try {
            observation.process("event-correlation", "topic", () -> {
                assertThat(MDC.get("correlationId")).isEqualTo("event-correlation");
                return "PROCESSED";
            });
            observation.process("event-correlation", "topic", () -> "DUPLICATE");
            assertThatThrownBy(() -> observation.process("event-correlation", "topic", () -> {
                throw new IllegalStateException("private-payload");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(MDC.get("correlationId")).isEqualTo("previous");
            assertThat(meters.counter("insurance.kafka.consumer.processed").count()).isEqualTo(1);
            assertThat(meters.counter("insurance.kafka.consumer.duplicate").count()).isEqualTo(1);
        } finally { MDC.clear(); }
    }
}
