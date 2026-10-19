package com.insurance.platform.pricing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.ProducerListener;

@Configuration(proxyBeanMethods = false)
public class PricingKafkaConfiguration {
    @Bean
    ProducerListener<Object, Object> pricingProducerListener() {
        // PricingOutboxPublisher handles completion with bounded metadata-only logs.
        // Replace KafkaTemplate's default error listener, which can print the record contents.
        return new ProducerListener<>() {};
    }
}
