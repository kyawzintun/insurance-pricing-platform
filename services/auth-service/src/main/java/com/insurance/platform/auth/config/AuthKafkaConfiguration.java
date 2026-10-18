package com.insurance.platform.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.ProducerListener;

@Configuration(proxyBeanMethods = false)
public class AuthKafkaConfiguration {
    @Bean
    ProducerListener<Object, Object> authProducerListener() {
        // AuthOutboxPublisher handles completion with bounded metadata-only logs.
        // Replace KafkaTemplate's default error listener, which can print the record contents.
        return new ProducerListener<>() {};
    }
}
