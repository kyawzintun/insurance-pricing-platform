package com.insurance.platform.gateway.operations;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;

@Service
public class OperationsAggregator {
    private record Target(String id, String name, URI url, List<String> metrics, boolean credentials) {
    }

    private record Reading(String status, String error) {
    }

    private final LocalOperationsHealth local;
    private final WebClient client;
    private final Duration timeout;
    private final List<Target> targets;
    private static final List<String> OUTBOX = List.of("outboxPending", "outboxPublished", "outboxFailed");
    private static final List<String> CONSUMER = List.of("processedEvents", "duplicateEvents", "dlqCount");
    private static final Map<String, String> METRICS = Map.ofEntries(
            Map.entry("quoteCreated", "insurance.quote.created"), Map.entry("quoteFailed", "insurance.quote.failed"),
            Map.entry("pricingCallErrors", "insurance.pricing.client.errors"),
            Map.entry("pricingDuration", "insurance.pricing.client.duration"),
            Map.entry("outboxPending", "insurance.outbox.pending"), Map.entry("outboxPublished", "insurance.outbox.published"),
            Map.entry("outboxFailed", "insurance.outbox.failed"),
            Map.entry("processedEvents", "insurance.kafka.consumer.processed"),
            Map.entry("duplicateEvents", "insurance.kafka.consumer.duplicate"), Map.entry("dlqCount", "insurance.kafka.consumer.dlq"));

    public OperationsAggregator(LocalOperationsHealth local,
                                @Value("${operations.timeout-ms:1000}") int timeoutMs,
                                @Value("${operations.auth-url}") URI auth, @Value("${operations.quote-url}") URI quote,
                                @Value("${operations.pricing-url}") URI pricing, @Value("${operations.notification-url}") URI notification,
                                @Value("${operations.audit-url}") URI audit) {
        if (timeoutMs < 50 || timeoutMs > 10000)
            throw new IllegalArgumentException("Operations timeout must be 50..10000 ms");
        this.local = local;
        timeout = Duration.ofMillis(timeoutMs);
        client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .followRedirect(false).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs).responseTimeout(timeout)))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16384)).build();
        targets = List.of(new Target("auth-service", "Auth", auth, OUTBOX, true),
                new Target("quote-service", "Quote", quote, List.of("quoteCreated", "quoteFailed", "pricingCallErrors", "pricingDuration",
                        "outboxPending", "outboxPublished", "outboxFailed"), true),
                new Target("pricing-service", "Pricing", pricing, OUTBOX, true),
                new Target("notification-service", "Notification", notification, CONSUMER, false),
                new Target("audit-service", "Audit", audit, CONSUMER, true));
        for (var target : targets) {
            var url = target.url();
            if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost() == null
                    || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                    || !(url.getPath().isEmpty() || "/".equals(url.getPath())))
                throw new IllegalArgumentException("Operations URLs must be configured HTTP(S) origins without credentials or paths");
        }
    }

    public Mono<OperationsOverview> overview(String token, String correlation) {
        return Mono.zip(localCard(), Flux.fromIterable(targets).flatMapSequential(t -> remoteCard(t, token, correlation), 5).collectList())
                .map(tuple -> {
                    var services = new ArrayList<OperationsOverview.Service>();
                    services.add(tuple.getT1());
                    services.addAll(tuple.getT2());
                    // Pricing-client telemetry belongs to Quote. Display it on Pricing without duplicating counters.
                    var quote = services.get(2);
                    var pricing = services.get(3);
                    var metrics = new LinkedHashMap<>(pricing.metrics());
                    for (String key : List.of("pricingCallErrors", "pricingAvgLatencyMs", "circuitBreakerState"))
                        metrics.put(key, quote.metrics().get(key));
                    services.set(3, new OperationsOverview.Service(pricing.id(), pricing.name(), pricing.status(), pricing.readiness(),
                            pricing.responseTimeMs(), pricing.checkedAt(), pricing.errorCode(), Collections.unmodifiableMap(metrics)));
                    String overall = services.stream().allMatch(s -> "UP".equals(s.status()) && "READY".equals(s.readiness())) ? "UP" : "DEGRADED";
                    return new OperationsOverview(overall, Instant.now(), List.copyOf(services));
                });
    }

    private Mono<OperationsOverview.Service> localCard() {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return Mono.zip(local.status(false).timeout(timeout).map(s -> new Reading(mapHealth(s), null))
                                    .onErrorReturn(new Reading("UNKNOWN", "LOCAL_HEALTH_UNAVAILABLE")),
                            local.status(true).timeout(timeout).map(s -> new Reading(mapReadiness(s), null))
                                    .onErrorReturn(new Reading("UNKNOWN", "LOCAL_HEALTH_UNAVAILABLE")))
                    .map(t -> card("api-gateway", "API Gateway", t.getT1(), t.getT2(), start, Map.of()));
        });
    }

    private Mono<OperationsOverview.Service> remoteCard(Target target, String token, String correlation) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return Mono.zip(health(target, false, correlation), health(target, true, correlation), metrics(target, token, correlation))
                    .map(t -> card(target.id(), target.name(), t.getT1(), t.getT2(), start, t.getT3()));
        });
    }

    private OperationsOverview.Service card(String id, String name, Reading health, Reading readiness, long start, Map<String, Object> metrics) {
        return new OperationsOverview.Service(id, name, health.status(), readiness.status(),
                Duration.ofNanos(System.nanoTime() - start).toMillis(), Instant.now(),
                health.error() != null ? health.error() : readiness.error(), metrics);
    }

    private Mono<Reading> health(Target target, boolean readiness, String correlation) {
        return get(target, "/actuator/health" + (readiness ? "/readiness" : ""), null, correlation, true)
                .map(json -> {
                    String status = json.path("status").asText("");
                    if (!List.of("UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN").contains(status))
                        return new Reading("UNKNOWN", "INVALID_RESPONSE");
                    return new Reading(readiness ? mapReadiness(status) : mapHealth(status), null);
                }).onErrorResume(failure -> Mono.just(new Reading(readiness ? "UNKNOWN" : "DOWN", safeError(failure))));
    }

    private Mono<JsonNode> get(Target target, String path, String token, String correlation, boolean health) {
        return client.get().uri(target.url().resolve(path)).headers(h -> {
            if (token != null && target.credentials()) h.setBearerAuth(token);
            if (correlation != null && correlation.matches("[A-Za-z0-9._-]{1,128}"))
                h.set("X-Correlation-ID", correlation);
        }).exchangeToMono(response -> {
            int status = response.statusCode().value();
            if (response.statusCode().is2xxSuccessful() || (health && status == 503))
                return response.bodyToMono(JsonNode.class).switchIfEmpty(Mono.error(new IllegalArgumentException()));
            return response.releaseBody().then(Mono.error(new IllegalArgumentException()));
        }).timeout(timeout);
    }

    private Mono<Map<String, Object>> metrics(Target target, String token, String correlation) {
        var calls = new ArrayList<Mono<Map.Entry<String, JsonNode>>>();
        for (var key : target.metrics())
            calls.add(get(target, "/actuator/metrics/" + METRICS.get(key), token, correlation, false)
                    .map(json -> Map.entry(key, json)).onErrorResume(e -> Mono.empty()));
        if ("quote-service".equals(target.id())) for (var state : List.of("closed", "open", "half_open"))
            calls.add(get(target, "/actuator/metrics/insurance.pricing.circuit.state?tag=state:" + state, token, correlation, false)
                    .map(json -> Map.entry("circuit:" + state, json)).onErrorResume(e -> Mono.empty()));
        return Flux.merge(calls).collectMap(Map.Entry::getKey, Map.Entry::getValue).map(raw -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (var key : target.metrics()) {
                if ("pricingDuration".equals(key)) {
                    Double count = measurement(raw.get(key), "COUNT"), total = measurement(raw.get(key), "TOTAL_TIME");
                    values.put("pricingAvgLatencyMs", count != null && count > 0 && total != null ? total * 1000 / count : null);
                } else values.put(key, measurement(raw.get(key), key.equals("outboxPending") ? "VALUE" : "COUNT"));
            }
            if ("quote-service".equals(target.id())) {
                var active = List.of("closed", "open", "half_open").stream()
                        .filter(s -> Double.valueOf(1).equals(measurement(raw.get("circuit:" + s), "VALUE"))).toList();
                values.put("circuitBreakerState", active.size() == 1 ? active.getFirst().toUpperCase(Locale.ROOT) : null);
            }
            return Collections.unmodifiableMap(values);
        });
    }

    static Double measurement(JsonNode json, String statistic) {
        if (json == null) return null;
        var entries = json.path("measurements");
        if (!entries.isArray()) return null;
        for (var entry : entries)
            if (statistic.equals(entry.path("statistic").asText()) && entry.path("value").isNumber()) {
                double value = entry.path("value").asDouble();
                return Double.isFinite(value) && value >= 0 ? value : null;
            }
        return null;
    }

    private static String mapHealth(String value) {
        return switch (value) {
            case "UP" -> "UP";
            case "DOWN", "OUT_OF_SERVICE" -> "DOWN";
            default -> "UNKNOWN";
        };
    }

    private static String mapReadiness(String value) {
        return switch (value) {
            case "UP" -> "READY";
            case "DOWN", "OUT_OF_SERVICE" -> "NOT_READY";
            default -> "UNKNOWN";
        };
    }

    private static String safeError(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause.getClass().getSimpleName().contains("Timeout"))
                return "TIMEOUT";
            if (cause instanceof ConnectException) return "CONNECTION_REFUSED";
        }
        return "INVALID_RESPONSE";
    }
}
