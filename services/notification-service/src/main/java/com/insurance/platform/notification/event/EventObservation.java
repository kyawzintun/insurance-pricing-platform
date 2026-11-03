package com.insurance.platform.notification.event;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class EventObservation {
    private final MeterRegistry meters;
    public EventObservation(MeterRegistry meters) { this.meters = meters; }
    public <T> T process(String correlation, String topic, Supplier<T> action) {
        var previous = MDC.getCopyOfContextMap();
        if (correlation != null && correlation.matches("[A-Za-z0-9._-]{1,128}")) MDC.put("correlationId", correlation);
        try {
            T result = action.get();
            String outcome = "DUPLICATE".equals(String.valueOf(result)) ? "duplicate" : "processed";
            meters.counter("insurance.kafka.consumer." + outcome).increment();
            return result;
        } finally {
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
