package com.insurance.platform.pricing.config;

import java.time.Duration;
import java.util.Collection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

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
                new JwtClaimValidator<String>("sub", value -> value != null && !value.isBlank()),
                jwt -> {
                    Object value = jwt.getClaims().get("roles");
                    boolean valid = "service".equals(jwt.getClaimAsString("token_use"))
                            || value instanceof Collection<?> roles && !roles.isEmpty()
                            && roles.stream().allMatch(role -> role instanceof String text && !text.isBlank());
                    return valid ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token"));
                }));
        return decoder;
    }
}
