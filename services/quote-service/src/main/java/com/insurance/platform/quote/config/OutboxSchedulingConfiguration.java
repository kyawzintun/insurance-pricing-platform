package com.insurance.platform.quote.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true")
public class OutboxSchedulingConfiguration {}
