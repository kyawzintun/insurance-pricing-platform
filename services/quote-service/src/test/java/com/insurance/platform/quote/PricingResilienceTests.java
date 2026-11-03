package com.insurance.platform.quote;

import com.insurance.platform.quote.client.PricingClient;
import com.insurance.platform.quote.client.ServiceTokenClient;
import com.insurance.platform.quote.config.PricingResilienceConfiguration;
import com.insurance.platform.quote.exception.QuoteException;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PricingResilienceTests {
    @Test void opensRejectsWithoutHttpOrAuthAndRecoversThroughHalfOpen() throws Exception {
        var calls = new AtomicInteger();
        var status = new AtomicInteger(503);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/pricing/calculate", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            var body = QuoteFixtures.PRICE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        var tokens = mock(ServiceTokenClient.class);
        when(tokens.token(anyString())).thenReturn("sensitive-machine-token");
        var meters = new SimpleMeterRegistry();
        var breaker = new PricingResilienceConfiguration().pricingCircuitBreaker(meters, 50, 2, 2, Duration.ofMillis(100));
        try (var validation = Validation.buildDefaultValidatorFactory();
             var client = new PricingClient("http://127.0.0.1:"+server.getAddress().getPort(), Duration.ofSeconds(1), Duration.ofMillis(100), validation.getValidator(), tokens)) {
            client.configureObservability(breaker, meters);
            for (int i=0;i<2;i++) assertThatThrownBy(() -> client.calculate(QuoteFixtures.request())).isInstanceOf(QuoteException.class);
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            clearInvocations(tokens);
            assertThatThrownBy(() -> client.calculate(QuoteFixtures.request())).isInstanceOf(QuoteException.class);
            verifyNoInteractions(tokens); assertThat(calls.get()).isEqualTo(2);
            assertThat(meters.counter("insurance.pricing.client.errors").count()).isEqualTo(3);
            assertThat(meters.timer("insurance.pricing.client.duration").count()).isEqualTo(3);
            status.set(200); Thread.sleep(130);
            assertThat(client.calculate(QuoteFixtures.request()).finalPremium()).isEqualByComparingTo("11200");
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        } finally { server.stop(0); }
    }

    @Test void slowPricingAndSlowAuthFailWithinConfiguredTimeouts() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(200); exchange.sendResponseHeaders(503, -1); }
            catch (Exception ignored) { } finally { exchange.close(); }
        }); server.start();
        String url = "http://127.0.0.1:"+server.getAddress().getPort();
        var tokens = mock(ServiceTokenClient.class); when(tokens.token(anyString())).thenReturn("machine-token");
        try (var validation = Validation.buildDefaultValidatorFactory();
             var client = new PricingClient(url, Duration.ofSeconds(1), Duration.ofMillis(30), validation.getValidator(), tokens);
             var auth = new ServiceTokenClient(url, "quote-service", "secret", Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(30))) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.calculate(QuoteFixtures.request())).isInstanceOf(QuoteException.class).hasMessage("Pricing service is unavailable");
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
            assertThatThrownBy(() -> auth.token("timeout-test")).isInstanceOf(QuoteException.class).hasMessageNotContaining("secret");
        } finally { server.stop(0); }
    }
}
