package com.insurance.platform.pricing;

import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

final class PricingTestIdentity {
    static final java.util.UUID ADMIN = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
    static void authenticate() {
        var jwt = Jwt.withTokenValue("test-only-redacted-token").header("alg", "HS256").subject(ADMIN.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
