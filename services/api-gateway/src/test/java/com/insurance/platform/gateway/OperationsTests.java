package com.insurance.platform.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OperationsTests {
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final HttpServer[] SERVERS = new HttpServer[5];
    private static final int[] STATUS = new int[5];
    private static volatile int slow = -1, malformed = -1;
    private static volatile boolean missing;
    private static volatile String circuit = "closed";
    private static volatile CountDownLatch barrier;
    private static volatile String credentialError;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static {
        try {
            for (int i = 0; i < SERVERS.length; i++) {
                final int index = i;
                SERVERS[i] = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                SERVERS[i].setExecutor(POOL);
                SERVERS[i].createContext("/actuator/", exchange -> {
                    try {
                        String path = exchange.getRequestURI().getPath();
                        boolean health = path.startsWith("/actuator/health");
                        String auth = exchange.getRequestHeaders().getFirst("Authorization");
                        if ((health || index == 3) && auth != null) credentialError = "Unexpected credential forwarding";
                        if (!health && index != 3 && (auth == null || !auth.startsWith("Bearer "))) credentialError = "Missing metric credentials";
                        if (index == slow) Thread.sleep(1000);
                        var gate = barrier;
                        if (gate != null && path.equals("/actuator/health")) { gate.countDown(); gate.await(2, TimeUnit.SECONDS); }
                        int status = health ? STATUS[index] : missing ? 404 : 200;
                        String body;
                        if (health) body = index == malformed ? "{\"status\":\"private-host-secret\"}"
                                : "{\"status\":\"" + (status == 200 ? "UP" : "DOWN") + "\",\"private\":\"secret-host Authorization\"}";
                        else if (path.endsWith("insurance.pricing.client.duration")) body = "{\"baseUnit\":\"seconds\",\"measurements\":[{\"statistic\":\"COUNT\",\"value\":10},{\"statistic\":\"TOTAL_TIME\",\"value\":2.5}]}";
                        else if (path.endsWith("insurance.pricing.circuit.state")) body = "{\"measurements\":[{\"statistic\":\"VALUE\",\"value\":" + (exchange.getRequestURI().getQuery().endsWith(circuit) ? 1 : 0) + "}]}";
                        else body = "{\"measurements\":[{\"statistic\":\"COUNT\",\"value\":10},{\"statistic\":\"VALUE\",\"value\":4}],\"private\":\"secret-host Authorization\"}";
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
                    } catch (Exception ignored) { } finally { exchange.close(); }
                });
                SERVERS[i].start();
            }
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        String[] ids = {"auth", "quote", "pricing", "notification", "audit"};
        for (int i = 0; i < ids.length; i++) {
            final int index = i;
            registry.add("operations." + ids[i] + "-url", () -> "http://127.0.0.1:" + SERVERS[index].getAddress().getPort());
        }
        registry.add("operations.timeout-ms", () -> 400);
    }
    @AfterAll static void close() { for (var server : SERVERS) server.stop(0); POOL.shutdownNow(); }
    @BeforeEach void reset() { Arrays.fill(STATUS, 200); slow = -1; malformed = -1; missing = false; circuit = "closed"; barrier = null; credentialError = null; }
    @LocalServerPort int port;
    private HttpResponse<String> get(String role, String path) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (role != null) request.header("Authorization", "Bearer " + RsaTestTokens.sign("{\"sub\":\"00000000-0000-0000-0000-000000000001\",\"roles\":[\"" + role + "\"],\"iss\":\"insurance-auth-service\",\"exp\":" + (Instant.now().getEpochSecond() + 300) + "}"));
            return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private JsonNode overview() throws Exception {
        var response = get("ADMIN", "/api/v1/admin/operations");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain("secret-host", "Authorization", "localhost", "measurements", "availableTags", "private-host-secret");
        return JSON.readTree(response.body());
    }
    @Test void authoritativeAdminSecurityAndNoProxy() throws Exception {
        assertThat(get(null, "/api/v1/admin/operations").statusCode()).isEqualTo(401);
        assertThat(get("CUSTOMER", "/api/v1/admin/operations").statusCode()).isEqualTo(403);
        assertThat(get("ADMIN", "/api/v1/admin/operations/proxy/env").statusCode()).isEqualTo(404);
        assertThat(overview().path("services").size()).isEqualTo(6);
    }
    @Test void healthyOverviewMapsEveryCuratedMetricAndDoesNotRelayCredentialsToNotification() throws Exception {
        var body = overview();
        assertThat(body.path("overallStatus").asText()).isEqualTo("UP");
        var services = body.path("services");
        assertThat(services.valueStream().map(s -> s.path("id").asText()).toList()).containsExactly("api-gateway", "auth-service", "quote-service", "pricing-service", "notification-service", "audit-service");
        for (var service : services) {
            assertThat(service.path("status").asText()).isEqualTo("UP");
            assertThat(service.path("readiness").asText()).isEqualTo("READY");
            assertThat(service.path("responseTimeMs").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(service.path("checkedAt").asText()).isNotBlank();
        }
        var quote = services.get(2).path("metrics");
        for (var key : List.of("quoteCreated", "quoteFailed", "pricingCallErrors", "outboxPublished", "outboxFailed")) assertThat(quote.path(key).asDouble()).isEqualTo(10);
        assertThat(quote.path("outboxPending").asDouble()).isEqualTo(4);
        for (int i : List.of(4, 5)) for (var key : List.of("processedEvents", "duplicateEvents", "dlqCount")) assertThat(services.get(i).path("metrics").path(key).asDouble()).isEqualTo(10);
        assertThat(services.get(3).path("metrics").path("pricingAvgLatencyMs").asDouble()).isEqualTo(250);
        assertThat(services.get(3).path("metrics").path("circuitBreakerState").asText()).isEqualTo("CLOSED");
        assertThat(credentialError).isNull();
    }
    @Test void outagesAndRecoveryPreserveOtherCards() throws Exception {
        STATUS[2] = 503; STATUS[4] = 503;
        var body = overview(); assertThat(body.path("overallStatus").asText()).isEqualTo("DEGRADED");
        assertThat(body.path("services").get(3).path("status").asText()).isEqualTo("DOWN");
        assertThat(body.path("services").get(1).path("status").asText()).isEqualTo("UP");
        STATUS[2] = 200; STATUS[4] = 200;
        assertThat(overview().path("overallStatus").asText()).isEqualTo("UP");
    }
    @Test void timeoutAndMalformedResponsesHaveOnlySafeErrors() throws Exception {
        slow = 4; malformed = 2;
        var body = overview();
        assertThat(body.path("services").get(5).path("errorCode").asText()).isEqualTo("TIMEOUT");
        assertThat(body.path("services").get(5).path("status").asText()).isEqualTo("DOWN");
        assertThat(body.path("services").get(3).path("errorCode").asText()).isEqualTo("INVALID_RESPONSE");
        assertThat(body.path("overallStatus").asText()).isEqualTo("DEGRADED");
    }
    @Test void missingMetricsAreNullWithoutChangingHealth() throws Exception {
        missing = true;
        var body = overview();
        assertThat(body.path("overallStatus").asText()).isEqualTo("UP");
        assertThat(body.path("services").get(2).path("metrics").path("quoteCreated").isNull()).isTrue();
        assertThat(body.path("services").get(3).path("metrics").path("circuitBreakerState").isNull()).isTrue();
    }
    @Test void fanoutIsConcurrentAndCircuitStatesComeFromQuote() throws Exception {
        barrier = new CountDownLatch(5); circuit = "half_open";
        var body = overview();
        assertThat(barrier.getCount()).isZero();
        assertThat(body.path("overallStatus").asText()).isEqualTo("UP");
        assertThat(body.path("services").get(3).path("metrics").path("circuitBreakerState").asText()).isEqualTo("HALF_OPEN");
    }
}
