package com.insurance.platform.pricing.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PricingConfiguration {
    @Bean
    Clock pricingClock() { return Clock.systemUTC(); }
}
