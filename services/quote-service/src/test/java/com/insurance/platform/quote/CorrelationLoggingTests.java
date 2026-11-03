package com.insurance.platform.quote;

import com.insurance.platform.quote.config.CorrelationLoggingFilter;
import com.insurance.platform.quote.event.QuoteCorrelationId;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationLoggingTests {
    @Test void reusesSafeIdAndCleansThreadWhenRequestFails() {
        var request = new MockHttpServletRequest(); request.addHeader("X-Correlation-ID", "customer-id");
        var response = new MockHttpServletResponse();
        try {
            assertThatThrownBy(() -> new CorrelationLoggingFilter().doFilter(request, response, (req, res) -> {
                assertThat(MDC.get("correlationId")).isEqualTo("customer-id");
                assertThat(new QuoteCorrelationId().current()).isEqualTo("customer-id");
                throw new ServletException("failure");
            })).isInstanceOf(ServletException.class);
            assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("customer-id");
            assertThat(MDC.get("correlationId")).isNull();
        } finally { MDC.clear(); }
    }
}
