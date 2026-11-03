package com.insurance.platform.auth.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded existing diagnostic ID. Scope is always restored when a pooled thread is returned. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationLoggingFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader("X-Correlation-ID");
        String id = incoming != null && incoming.matches("[A-Za-z0-9._-]{1,128}")
                ? incoming : UUID.randomUUID().toString();
        var previous = MDC.getCopyOfContextMap();
        MDC.put("correlationId", id);
        response.setHeader("X-Correlation-ID", id);
        try {
            chain.doFilter(request, response);
        } finally {
            LoggerFactory.getLogger(getClass()).info("action=http-request method={} status={}", request.getMethod(), response.getStatus());
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
