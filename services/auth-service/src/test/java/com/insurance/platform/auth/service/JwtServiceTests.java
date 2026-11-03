package com.insurance.platform.auth.service;

import com.insurance.platform.auth.RsaTestTokens;
import com.insurance.platform.auth.config.JwtConfiguration;
import com.insurance.platform.auth.config.RsaKeys;
import com.insurance.platform.auth.dto.LoginRequest;
import com.insurance.platform.auth.dto.RegisterRequest;
import com.insurance.platform.auth.dto.TokenResponse;
import com.insurance.platform.auth.entity.User;
import com.insurance.platform.auth.enums.Role;
import com.insurance.platform.auth.enums.UserStatus;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTests {
    @Test
    void configuredLifetimeAndAdminRoleAreSignedAndTamperingIsRejected() {
        var publicResource = new ClassPathResource("keys/dev-jwt-public.pem");
        var privateResource = new ClassPathResource("keys/dev-jwt-private.pem");
        var key = RsaKeys.publicKey(publicResource);
        var encoder = new JwtConfiguration().jwtEncoder(privateResource, publicResource);
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var service = new JwtService(encoder,
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5), "test-issuer");
        var user = new User(UUID.randomUUID(), "admin@example.com", "not-used", "Local", "Admin",
                Role.ADMIN, UserStatus.ACTIVE, now);
        var response = service.issue(user);
        var decoder = NimbusJwtDecoder.withPublicKey(key).build();
        var jwt = decoder.decode(response.accessToken());
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
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
        assertThatThrownBy(() -> new JwtService(encoder,
                Clock.systemUTC(), Duration.ZERO, "test")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidPrivateKeysFailWithoutExposingTheirValues() {
        var config = new JwtConfiguration();
        for (var value : List.of("", "not-base64-secret!", Base64.getEncoder().encodeToString(new byte[8]))) {
            assertThatThrownBy(() -> config.jwtEncoder(new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8)), new ClassPathResource("keys/dev-jwt-public.pem"))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUTH_JWT_PRIVATE_KEY");
        }
    }

    @Test
    void credentialDtosRedactToString() {
        assertThat(new RegisterRequest("a@example.com", "sensitive-value", "A", "B").toString()).doesNotContain("sensitive-value");
        assertThat(new LoginRequest("a@example.com", "sensitive-value").toString()).doesNotContain("sensitive-value");
        assertThat(new TokenResponse("sensitive-token", "Bearer", 900).toString()).doesNotContain("sensitive-token");
    }
    @Test void mismatchedPairFailsStartupAndUnrelatedPublicKeyCannotVerify() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var other = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(other.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
        var unrelated = new ByteArrayResource(pem.getBytes(StandardCharsets.US_ASCII));
        var privateKey = new ClassPathResource("keys/dev-jwt-private.pem");
        assertThatThrownBy(() -> new JwtConfiguration().jwtEncoder(privateKey, unrelated))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("matching AUTH_JWT_PUBLIC_KEY");
        String token = RsaTestTokens.sign("{\"sub\":\"user\",\"exp\":"+(Instant.now().getEpochSecond()+300)+"}");
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) other.getPublic()).build();
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

}
