package com.insurance.platform.quote.client;

import com.insurance.platform.quote.dto.CreateQuoteRequest;
import com.insurance.platform.quote.enums.PricingRuleType;
import com.insurance.platform.quote.event.QuoteCorrelationId;
import com.insurance.platform.quote.exception.QuoteException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException.Unauthorized;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class PricingClient implements AutoCloseable {
    private final HttpClient httpClient;
    private final RestClient client;
    private final Validator validator;
    private final ServiceTokenClient tokens;

    public PricingClient(@Value("${pricing.service.url}") String baseUrl,
                         @Value("${pricing.service.connect-timeout}") Duration connectTimeout,
                         @Value("${pricing.service.read-timeout}") Duration readTimeout, Validator validator, ServiceTokenClient tokens) {
        if (connectTimeout.isNegative() || connectTimeout.isZero() || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("Pricing HTTP timeouts must be positive");
        }

        httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.validator = validator;
        this.tokens = tokens;
    }

    private CircuitBreaker breaker =
            CircuitBreaker.ofDefaults("pricing");
    private MeterRegistry meters = new SimpleMeterRegistry();

    @Autowired
    public void configureObservability(CircuitBreaker breaker,
                                       MeterRegistry meters) {
        this.breaker = breaker;
        this.meters = meters;
    }

    public PricingResult calculate(CreateQuoteRequest request) {
        var sample = Timer.start(meters);
        try {
            // Token endpoint failure must not count against Pricing's breaker.
            String correlation = new QuoteCorrelationId().current();
            var result = validate(breaker.executeSupplier(() -> {
                String token = tokens.token(correlation);
                return callWithTokenRefresh(request, token, correlation);
            }));
            LoggerFactory.getLogger(getClass()).info("action=pricing-call result=success");
            return result;
        } catch (RuntimeException failure) {
            meters.counter("insurance.pricing.client.errors").increment();
            LoggerFactory.getLogger(getClass()).warn("action=pricing-call result=failure failureType={}", failure.getClass().getSimpleName());
            if (failure instanceof RestClientException && !(failure instanceof ResourceAccessException)) throw QuoteException.pricingError();
            if (failure instanceof ResourceAccessException || failure instanceof CallNotPermittedException) throw QuoteException.pricingUnavailable();
            throw failure;
        } finally {
            sample.stop(meters.timer("insurance.pricing.client.duration"));
        }
    }

    private PricingResult callWithTokenRefresh(CreateQuoteRequest request, String token, String correlation) {
        try {
            return call(request, token, correlation);
        } catch (Unauthorized ex) {
            tokens.invalidate(token);
            return call(request, tokens.token(correlation), correlation);
        }
    }

    private PricingResult call(CreateQuoteRequest request, String token, String correlation) {
        return client
                .post()
                .uri("/internal/v1/pricing/calculate")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(token))
                .header("X-Correlation-ID", correlation)
                .body(PricingRequest.from(request))
                .retrieve()
                .body(PricingResult.class);
    }

    private PricingResult validate(PricingResult result) {
        if (result == null || !validator.validate(result).isEmpty()) throw QuoteException.pricingError();
        if (result.calculatedAt().isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || result.calculatedAt().isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            throw QuoteException.pricingError();
        }
        var ordered = result.adjustments().stream().sorted(Comparator.comparing(PricingResult.Adjustment::sequenceNumber)).toList();
        var before = result.basePremium();
        var categories = new HashSet<PricingRuleType>();
        int sequence = 1;
        for (var adjustment : ordered) {
            if (adjustment.sequenceNumber() != sequence++ || adjustment.amountBefore().compareTo(before) != 0
                    || adjustment.ruleType() == PricingRuleType.BASE_PREMIUM || !categories.add(adjustment.ruleType())) {
                throw QuoteException.pricingError();
            }
            before = adjustment.amountAfter();
        }
        if (before.compareTo(result.finalPremium()) != 0) throw QuoteException.pricingError();
        // Check snapshot integrity, not rule matching or pricing arithmetic: Pricing owns those calculations.
        return new PricingResult(result.basePricingRuleId(), result.basePricingRuleVersion(), result.basePremium(),
                result.finalPremium(), result.currency(), result.calculatedAt(), ordered);
    }

    @PreDestroy
    @Override
    public void close() {
        httpClient.close();
    }
}
