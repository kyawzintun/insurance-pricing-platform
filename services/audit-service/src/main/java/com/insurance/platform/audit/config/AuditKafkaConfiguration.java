package com.insurance.platform.audit.config;

import com.insurance.platform.audit.event.QuoteDeadLetterRecoverer;
import com.insurance.platform.audit.exception.InvalidQuoteEventException;
import com.insurance.platform.audit.exception.InvalidUserEventException;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
public class AuditKafkaConfiguration {
    @Bean
    Clock auditClock() { return Clock.systemUTC(); }

    @Bean
    QuoteDeadLetterRecoverer auditDeadLetterRecoverer(KafkaTemplate<byte[], byte[]> kafka, Clock clock,
            @Value("${spring.kafka.consumer.group-id}") String group,
            @Value("${kafka.consumer.max-attempts:3}") int maxAttempts,
            @Value("${user.events.topic:insurance.user.events}") String userTopic,
            @Value("${user.events.dlq-topic:insurance.user.events.dlq}") String userDlqTopic) {
        if (maxAttempts < 1 || maxAttempts > 10) throw new IllegalArgumentException("Consumer max attempts must be 1..10");
        return new QuoteDeadLetterRecoverer(kafka, clock, group, maxAttempts, userTopic, userDlqTopic);
    }

    @Bean
    public DefaultErrorHandler auditErrorHandler(QuoteDeadLetterRecoverer recoverer,
            @Value("${kafka.consumer.max-attempts:3}") int maxAttempts,
            @Value("${kafka.consumer.backoff-ms:1000}") long backoffMs) {
        if (maxAttempts < 1 || maxAttempts > 10 || backoffMs < 0 || backoffMs > 10000)
            throw new IllegalArgumentException("Consumer attempts must be 1..10 and backoff 0..10000 ms");
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(backoffMs, maxAttempts - 1L));
        // All processing exceptions retry; only our validated permanent-input failures bypass retries.
        handler.setClassifications(Map.of(InvalidQuoteEventException.class, false, InvalidUserEventException.class, false), true);
        handler.setResetStateOnExceptionChange(false);
        handler.setResetStateOnRecoveryFailure(true);
        handler.setRetryListeners(recoverer);
        handler.setAckAfterHandle(true); // RECORD mode: completion only if processing/recovery returns successfully.
        handler.setLogLevel(KafkaException.Level.DEBUG);
        return handler;
    }

    @Bean
    ProducerListener<Object, Object> dlqProducerListener() {
        // Recoverer logs bounded coordinates; the default listener can print failed record contents.
        return new ProducerListener<>() {};
    }
}
