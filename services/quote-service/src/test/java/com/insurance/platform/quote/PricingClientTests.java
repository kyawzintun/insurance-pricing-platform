package com.insurance.platform.quote;

import com.insurance.platform.quote.client.PricingClient;
import com.insurance.platform.quote.exception.QuoteException;
import jakarta.validation.Validation;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PricingClientTests {
    @Test void connectionRefusalIsSafeUnavailable() throws Exception {
        int unusedPort;
        try(var socket=new ServerSocket(0)) { unusedPort=socket.getLocalPort(); }
        try(var validators=Validation.buildDefaultValidatorFactory()) {
            try (var client=new PricingClient("http://127.0.0.1:"+unusedPort,Duration.ofMillis(200),Duration.ofMillis(200),validators.getValidator())) {
            assertThatThrownBy(() -> client.calculate(QuoteFixtures.request())).isInstanceOfSatisfying(QuoteException.class,
                    ex -> { assertThat(ex.code()).isEqualTo("PRICING_SERVICE_UNAVAILABLE"); assertThat(ex.getMessage()).doesNotContain("localhost",Integer.toString(unusedPort)); });
            }
        }
    }
}
