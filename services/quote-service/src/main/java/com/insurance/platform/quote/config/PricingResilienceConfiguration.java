package com.insurance.platform.quote.config;

import com.insurance.platform.quote.exception.QuoteException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;

@Configuration(proxyBeanMethods = false)
public class PricingResilienceConfiguration {
    @Bean
    public CircuitBreaker pricingCircuitBreaker(MeterRegistry meters,
            @Value("${pricing.circuit-breaker.failure-rate:50}") float failureRate,
            @Value("${pricing.circuit-breaker.minimum-calls:5}") int minimum,
            @Value("${pricing.circuit-breaker.window:10}") int window,
            @Value("${pricing.circuit-breaker.open-wait:10s}") Duration wait) {
        var breaker = CircuitBreaker.of("pricing", CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRate).minimumNumberOfCalls(minimum).slidingWindowSize(window)
                .waitDurationInOpenState(wait).permittedNumberOfCallsInHalfOpenState(1)
                .ignoreExceptions(HttpClientErrorException.class, QuoteException.class).build());
        for (var state : new CircuitBreaker.State[]{CircuitBreaker.State.CLOSED, CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN}) {
            meters.gauge("insurance.pricing.circuit.state", List.of(
                    Tag.of("state", state.name().toLowerCase(Locale.ROOT))),
                    breaker, b -> b.getState() == state ? 1 : 0);
        }
        breaker.getEventPublisher().onStateTransition(event -> LoggerFactory.getLogger(getClass())
                .info("action=pricing-circuit-transition transition={}", event.getStateTransition()));
        return breaker;
    }
}
