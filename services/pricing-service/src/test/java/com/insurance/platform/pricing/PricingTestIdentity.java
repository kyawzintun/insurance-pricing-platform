package com.insurance.platform.pricing;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

final class PricingTestIdentity {
    static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static void authenticate() {
        var jwt = Jwt.withTokenValue("test-only-redacted-token").header("alg", "RS256").subject(ADMIN.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
