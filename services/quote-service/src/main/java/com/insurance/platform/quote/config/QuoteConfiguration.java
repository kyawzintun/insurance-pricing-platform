package com.insurance.platform.quote.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class QuoteConfiguration {
    @Bean
    Clock quoteClock() { return Clock.systemUTC(); }
}
