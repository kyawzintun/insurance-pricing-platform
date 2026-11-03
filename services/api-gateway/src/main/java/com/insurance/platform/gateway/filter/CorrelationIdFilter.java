package com.insurance.platform.gateway.filter;

import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements WebFilter {
    private static final String HEADER = "X-Correlation-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        // Bound untrusted input; this is a diagnostic identifier, never identity/authentication.
        var id = incoming != null && incoming.matches("[A-Za-z0-9._-]{1,128}")
                ? incoming : UUID.randomUUID().toString();
        var request = exchange.getRequest().mutate().headers(headers -> headers.set(HEADER, id)).build();
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, id);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build()).doFinally(signal ->
                LoggerFactory.getLogger(getClass()).info(
                        "action=http-request correlationId={} method={} status={}", id,
                        request.getMethod(), exchange.getResponse().getStatusCode()));
    }
}
