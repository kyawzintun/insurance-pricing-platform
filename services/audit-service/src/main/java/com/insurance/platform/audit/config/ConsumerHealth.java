package com.insurance.platform.audit.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/**
 * Local container lifecycle only: broker reachability and consumer lag are separate signals.
 */
@Component("kafkaConsumer")
public class ConsumerHealth implements HealthIndicator {
    private final KafkaListenerEndpointRegistry containers;

    @Value("${spring.kafka.listener.auto-startup:true}")
    private boolean enabled = true;

    public ConsumerHealth(KafkaListenerEndpointRegistry containers) {
        this.containers = containers;
    }

    @Override
    public Health health() {
        if (!enabled) return Health.unknown().build();
        var all = containers.getListenerContainers();
        return !all.isEmpty() && all.stream().allMatch(c -> c.isRunning())
                ? Health.up().build() : Health.down().build();
    }
}
