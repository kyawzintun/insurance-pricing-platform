package com.insurance.platform.gateway.operations;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Read the same local Actuator contributors without calling Gateway over HTTP.
 */
@Component
public class LocalOperationsHealth {
    private final HealthEndpoint health;

    public LocalOperationsHealth(HealthEndpoint health) {
        this.health = health;
    }

    public Mono<String> status(boolean readiness) {
        return Mono.fromCallable(() -> {
            var value = readiness ? health.healthForPath("readiness") : health.health();
            return value == null ? "UNKNOWN" : value.getStatus().getCode();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
