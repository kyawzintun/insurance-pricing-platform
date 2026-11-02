package com.insurance.platform.quote.client;

import com.insurance.platform.quote.exception.QuoteException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

/**
 * Instance-local cache. The monitor makes acquisition single flight, including 401 replacement.
 */
@Component
public class ServiceTokenClient implements AutoCloseable {
    private final HttpClient http;
    private final RestClient client;
    private final String id, secret;
    private final Duration margin;
    private String cached;
    private Instant staleAt = Instant.MIN;

    public ServiceTokenClient(@Value("${service-auth.token-url:http://localhost:8081/api/v1/auth/service-token}") String url,
                              @Value("${service-auth.client-id:quote-service}") String id,
                              @Value("${service-auth.client-secret:}") String secret,
                              @Value("${service-auth.expiry-margin:30s}") Duration margin,
                              @Value("${pricing.service.connect-timeout:2s}") Duration connect,
                              @Value("${pricing.service.read-timeout:5s}") Duration read) {
        if (margin.isNegative()) {
            throw new IllegalArgumentException("Invalid token expiry margin");
        }

        this.id = id;
        this.secret = secret;
        this.margin = margin;
        http = HttpClient.newBuilder().connectTimeout(connect).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(read);
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    public synchronized String token(String correlation) {
        if (cached != null && Instant.now().isBefore(staleAt)) {
            return cached;
        }
        
        try {
            if (secret.isBlank()) {
                throw QuoteException.pricingUnavailable();
            }

            var started = Instant.now();
            var response = client
                    .post()
                    .headers(h -> h.setBasicAuth(id, secret))
                    .header("X-Correlation-ID", correlation)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials")
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.access_token() == null || response.access_token().isBlank()
                    || !"Bearer".equals(response.token_type()) || response.expires_in() <= 0) {
                throw QuoteException.pricingUnavailable();
            }
            // Bound the margin for deliberately short development TTLs.
            long safety = Math.min(margin.toSeconds(), response.expires_in() / 2);
            staleAt = started.plusSeconds(response.expires_in() - safety);
            if (!Instant.now().isBefore(staleAt)) {
                throw QuoteException.pricingUnavailable();
            }
            cached = response.access_token();
            return cached;
        } catch (RuntimeException ex) {
            throw QuoteException.pricingUnavailable();
        }
    }

    public synchronized void invalidate(String rejected) {
        if (rejected.equals(cached)) {
            cached = null;
            staleAt = Instant.MIN;
        }
    }

    // No generated toString: credentials must never be rendered in diagnostics.
    private record TokenResponse(String access_token, String token_type, long expires_in) {
        @Override
        public String toString() {
            return "ServiceTokenResponse[redacted]";
        }
    }

    @PreDestroy
    public void close() {
        http.close();
    }
}
