package com.insurance.platform.gateway.config;

import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {
    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${auth.jwt.secret}") String encodedSecret,
                                  @Value("${auth.jwt.issuer}") String issuer) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("AUTH_JWT_SECRET must be Base64 encoding at least 32 bytes");
        }
        if (key.length < 32 || issuer.isBlank()) {
            throw new IllegalStateException("A signing key of at least 32 bytes and a nonblank JWT issuer are required");
        }
        var decoder = NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                // Strict local expiry: no acceptance window after exp.
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<Object>("exp", value -> value != null),
                new JwtClaimValidator<String>("sub", value -> value != null && !value.isBlank()),
                new JwtClaimValidator<Object>("roles", value -> value instanceof Collection<?> roles
                        && !roles.isEmpty() && roles.stream().allMatch(
                                role -> role instanceof String text && !text.isBlank()))));
        return decoder;
    }
}
