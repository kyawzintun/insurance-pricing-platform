package com.insurance.platform.notification.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;

@Configuration(proxyBeanMethods = false)
public class NotificationKafkaConfiguration {
    @Bean
    Clock notificationClock() {
        return Clock.systemUTC();
    }

    @Bean
    CommonErrorHandler notificationErrorHandler() {
        var handler = new CommonContainerStoppingErrorHandler();
        // Listener logs safe metadata at WARN; avoid routine stack traces from the container.
        handler.setLogLevel(KafkaException.Level.DEBUG);
        return handler;
    }
}
