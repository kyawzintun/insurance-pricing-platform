package com.insurance.platform.gateway.operations;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class OperationsController {
    private final OperationsAggregator operations;

    public OperationsController(OperationsAggregator operations) {
        this.operations = operations;
    }

    @GetMapping("/api/v1/admin/operations")
    public Mono<ResponseEntity<OperationsOverview>> overview(JwtAuthenticationToken authentication, ServerWebExchange exchange) {
        return operations.overview(authentication.getToken().getTokenValue(), exchange.getRequest().getHeaders().getFirst("X-Correlation-ID"))
                .map(body -> ResponseEntity.ok().header("Cache-Control", "no-store").body(body));
    }
}
