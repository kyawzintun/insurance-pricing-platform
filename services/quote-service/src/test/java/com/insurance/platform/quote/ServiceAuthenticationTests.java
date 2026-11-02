package com.insurance.platform.quote;

import com.insurance.platform.quote.client.*;
import com.insurance.platform.quote.exception.QuoteException;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ServiceAuthenticationTests {
    @Test void singleFlightCacheExpiryAndFailureRecovery() throws Exception {
        var requests = new AtomicInteger();
        var status = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
            assertThat(exchange.getRequestHeaders().getFirst("X-Correlation-ID")).isEqualTo("correlation-test");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("grant_type=client_credentials");
            byte[] body = "{\"access_token\":\"service-credential\",\"token_type\":\"Bearer\",\"expires_in\":2}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try (var tokens = new ServiceTokenClient("http://127.0.0.1:"+server.getAddress().getPort()+"/token", "quote-service", "secret", Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(2));
             var pool = Executors.newFixedThreadPool(10)) {
            var gate = new CountDownLatch(1);
            var tasks = new java.util.ArrayList<Future<String>>();
            for (int i=0;i<10;i++) tasks.add(pool.submit(() -> { gate.await(); return tokens.token("correlation-test"); }));
            gate.countDown();
            for(var task:tasks) assertThat(task.get(5, TimeUnit.SECONDS)).isEqualTo("service-credential");
            assertThat(requests.get()).isEqualTo(1);
            Thread.sleep(1100);
            tokens.token("correlation-test"); assertThat(requests.get()).isEqualTo(2);
            tokens.invalidate("service-credential"); status.set(401);
            assertThatThrownBy(() -> tokens.token("correlation-test")).isInstanceOf(QuoteException.class).hasMessageNotContaining("secret").hasMessageNotContaining("service-credential");
            status.set(200); assertThat(tokens.token("correlation-test")).isEqualTo("service-credential");
        } finally { server.stop(0); }
    }

    @Test void pricingRetriesOnlyOne401AndPreservesCorrelation() throws Exception {
        var calls = new AtomicInteger(); var responseStatus = new AtomicInteger(401);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/pricing/calculate", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer machine-token");
            assertThat(exchange.getRequestHeaders().getFirst("X-Correlation-ID")).isEqualTo("customer-correlation");
            exchange.getRequestBody().readAllBytes(); if (responseStatus.get() == 200 && calls.get() > 1) {
                byte[] body = QuoteFixtures.PRICE.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } else exchange.sendResponseHeaders(responseStatus.get() == 200 ? 401 : responseStatus.get(), -1);
            exchange.close();
        }); server.start();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", "customer-correlation");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        var tokens = org.mockito.Mockito.mock(ServiceTokenClient.class);
        org.mockito.Mockito.when(tokens.token(org.mockito.ArgumentMatchers.anyString())).thenReturn("machine-token");
        try (var validation = Validation.buildDefaultValidatorFactory();
             var pricing = new PricingClient("http://127.0.0.1:"+server.getAddress().getPort(), Duration.ofSeconds(1), Duration.ofSeconds(2), validation.getValidator(), tokens)) {
            assertThatThrownBy(() -> pricing.calculate(QuoteFixtures.request())).isInstanceOf(QuoteException.class);
            assertThat(calls.get()).isEqualTo(2);
            org.mockito.Mockito.verify(tokens).invalidate("machine-token");
            responseStatus.set(403); calls.set(0);
            assertThatThrownBy(() -> pricing.calculate(QuoteFixtures.request())).isInstanceOf(QuoteException.class);
            assertThat(calls.get()).isEqualTo(1);
            responseStatus.set(200); calls.set(0);
            assertThat(pricing.calculate(QuoteFixtures.request()).finalPremium()).isEqualByComparingTo("11200.00");
            assertThat(calls.get()).isEqualTo(2);
        } finally { server.stop(0); org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }
}
