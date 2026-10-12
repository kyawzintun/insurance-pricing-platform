package com.insurance.platform.quote.event;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class QuoteCorrelationId {
    public String current() {
        var attributes = RequestContextHolder.getRequestAttributes();
        var incoming = attributes instanceof ServletRequestAttributes servlet
                ? servlet.getRequest().getHeader("X-Correlation-ID") : null;
        // Same bounded diagnostic-ID convention as Gateway. Capture before leaving the request thread.
        return incoming != null && incoming.matches("[A-Za-z0-9._-]{1,128}")
                ? incoming : UUID.randomUUID().toString();
    }
}
