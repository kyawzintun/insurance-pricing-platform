package com.insurance.platform.quote.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.ProducerListener;

@Configuration(proxyBeanMethods = false)
public class QuoteKafkaConfiguration {
    @Bean
    ProducerListener<Object, Object> quoteProducerListener() {
        // QuoteEventPublisher handles completion with bounded metadata-only logs.
        // Replace KafkaTemplate's default error listener, which can print the record contents.
        return new ProducerListener<>() {};
    }
}
