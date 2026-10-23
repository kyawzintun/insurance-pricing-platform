package com.insurance.platform.quote.config;

import java.time.Duration;

import java.util.Collection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.*;

@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${auth.jwt.public-key}") Resource publicKey,
                                  @Value("${auth.jwt.issuer}") String issuer) {
        if (issuer.isBlank()) throw new IllegalStateException("JWT issuer must be nonblank");
        var decoder = NimbusJwtDecoder.withPublicKey(RsaKeys.publicKey(publicKey))
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                // Strict local expiry: no acceptance window after exp.
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<Object>("exp", value -> value != null),
                new JwtClaimValidator<String>("sub", JwtConfiguration::validSubject),
                new JwtClaimValidator<Object>("roles", value -> value instanceof Collection<?> roles
                        && !roles.isEmpty() && roles.stream().allMatch(
                                role -> role instanceof String text && !text.isBlank()))));
        return decoder;
    }
    private static boolean validSubject(String value) {
        if (value == null) return false;
        try { return java.util.UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException ex) { return false; }
    }
}
