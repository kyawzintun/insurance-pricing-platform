package com.insurance.platform.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.Duration;
import java.util.Collection;

@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {
    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${auth.jwt.public-key}") Resource publicKey,
                                  @Value("${auth.jwt.issuer}") String issuer) {
        if (issuer.isBlank()) {
            throw new IllegalStateException("JWT issuer must be nonblank");
        }
        var decoder = NimbusReactiveJwtDecoder.withPublicKey(RsaKeys.publicKey(publicKey))
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
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
