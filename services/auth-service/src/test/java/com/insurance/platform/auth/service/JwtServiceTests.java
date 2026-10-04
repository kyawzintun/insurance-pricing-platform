package com.insurance.platform.auth.service;

import com.insurance.platform.auth.dto.LoginRequest;
import com.insurance.platform.auth.dto.RegisterRequest;
import com.insurance.platform.auth.dto.TokenResponse;
import com.insurance.platform.auth.entity.User;
import com.insurance.platform.auth.enums.Role;
import com.insurance.platform.auth.enums.UserStatus;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;
import com.insurance.platform.auth.config.JwtConfiguration;
import static org.assertj.core.api.Assertions.*;

class JwtServiceTests {
    @Test
    void configuredLifetimeAndAdminRoleAreSignedAndTamperingIsRejected() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        var key = new SecretKeySpec(bytes, "HmacSHA256");
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var service = new JwtService(NimbusJwtEncoder.withSecretKey(key).build(),
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5), "test-issuer");
        var user = new User(UUID.randomUUID(), "admin@example.com", "not-used", "Local", "Admin",
                Role.ADMIN, UserStatus.ACTIVE, now);
        var response = service.issue(user);
        var decoder = NimbusJwtDecoder.withSecretKey(key).build();
        var jwt = decoder.decode(response.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
        assertThat(jwt.getIssuedAt()).isEqualTo(now);
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plusSeconds(300));
        assertThat(response.expiresIn()).isEqualTo(300);
        var parts = response.accessToken().split("\\.");
        var signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 1;
        var tampered = parts[0] + "." + parts[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> new JwtService(NimbusJwtEncoder.withSecretKey(key).build(),
                Clock.systemUTC(), Duration.ZERO, "test")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidSigningSecretsFailWithoutExposingTheirValues() {
        var config = new JwtConfiguration();
        for (var value : List.of("", "not-base64-secret!", Base64.getEncoder().encodeToString(new byte[8]))) {
            assertThatThrownBy(() -> config.jwtEncoder(value)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUTH_JWT_SECRET");
        }
    }

    @Test
    void credentialDtosRedactToString() {
        assertThat(new RegisterRequest("a@example.com", "sensitive-value", "A", "B").toString()).doesNotContain("sensitive-value");
        assertThat(new LoginRequest("a@example.com", "sensitive-value").toString()).doesNotContain("sensitive-value");
        assertThat(new TokenResponse("sensitive-token", "Bearer", 900).toString()).doesNotContain("sensitive-token");
    }
}
