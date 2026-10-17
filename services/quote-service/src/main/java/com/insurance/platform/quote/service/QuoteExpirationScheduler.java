package com.insurance.platform.quote.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.EnableScheduling;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "quote.expiration.enabled", havingValue = "true", matchIfMissing = true)
public class QuoteExpirationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(QuoteExpirationScheduler.class);
    private final QuoteExpirationService service;
    private final int batchSize;

    public QuoteExpirationScheduler(QuoteExpirationService service,
            @Value("${quote.expiration.batch-size:50}") int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Expiration batch size must be 1..500");
        }
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${quote.expiration.poll-interval:60000}",
            initialDelayString = "${quote.expiration.poll-interval:60000}")
    public void poll() {
        try { service.expireBatch(batchSize); }
        catch (Exception failure) {
            LOG.warn("Quote expiration batch rolled back failureType={}; next poll will retry", failure.getClass().getSimpleName());
        }
    }
}
