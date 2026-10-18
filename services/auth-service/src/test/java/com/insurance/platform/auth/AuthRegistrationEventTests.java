package com.insurance.platform.auth;
import com.insurance.platform.auth.service.*;
import com.insurance.platform.auth.repository.*;
import com.insurance.platform.auth.event.*;
import com.insurance.platform.auth.entity.OutboxEvent;
import com.insurance.platform.auth.dto.RegisterRequest;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AuthRegistrationEventTests {
    @Test void registrationBuildsOneSafeEnvelopeFromCanonicalUserAndSingleClockInstant() {
        var users=mock(UserRepository.class);var outbox=mock(OutboxRepository.class);
        var passwords=mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        when(passwords.encode(any())).thenReturn("$2a$12$secretHash");
        var clock=mock(Clock.class);var now=Instant.parse("2026-10-17T01:02:03Z");when(clock.instant()).thenReturn(now);
        var correlation=mock(AuthCorrelationId.class);when(correlation.current()).thenReturn("gateway-correlation");
        var mapper=JsonMapper.builder().build();
        var service=new AuthService(users,passwords,mock(JwtService.class),clock,outbox,correlation,mapper);
        var response=service.register(new RegisterRequest(" CANONICAL@Example.COM ","Test-password-42!","First","Last"));
        var capture=ArgumentCaptor.forClass(OutboxEvent.class);verify(outbox).saveAndFlush(capture.capture());
        var row=capture.getValue();var json=mapper.readTree(row.getPayload());
        assertThat(json.size()).isEqualTo(9);assertThat(json.path("eventId").asText()).isEqualTo(row.getEventId().toString());
        assertThat(json.path("eventType").asText()).isEqualTo("UserRegistered");assertThat(json.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(json.path("aggregateType").asText()).isEqualTo("User");assertThat(json.path("aggregateId").asText()).isEqualTo(response.id().toString());
        assertThat(json.path("producer").asText()).isEqualTo("auth-service");assertThat(json.path("correlationId").asText()).isEqualTo("gateway-correlation");
        assertThat(json.path("occurredAt").asText()).isEqualTo(now.toString());assertThat(json.path("data").size()).isEqualTo(5);
        assertThat(json.path("data").path("registeredAt").asText()).isEqualTo(now.toString());
        assertThat(json.path("data").path("email").asText()).isEqualTo("canonical@example.com");
        assertThat(json.path("data").path("role").asText()).isEqualTo("CUSTOMER");assertThat(json.path("data").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(row.getPayload()).doesNotContain("password","Hash","$2a$","accessToken","refreshToken","Authorization","First","Last");
        verify(clock).instant();verify(correlation).current();
    }
}
