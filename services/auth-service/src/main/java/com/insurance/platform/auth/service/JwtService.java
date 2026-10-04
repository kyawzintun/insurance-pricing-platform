package com.insurance.platform.auth.service;

import com.insurance.platform.auth.dto.TokenResponse;
import com.insurance.platform.auth.entity.User;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration lifetime;
    private final String issuer;

    public JwtService(JwtEncoder encoder, Clock clock,
                      @Value("${auth.jwt.access-token-ttl}") Duration lifetime,
                      @Value("${auth.jwt.issuer}") String issuer) {
        if (lifetime == null || lifetime.getSeconds() < 1 || lifetime.getNano() != 0) {
            throw new IllegalArgumentException("JWT access-token TTL must be a positive whole number of seconds");
        }
        this.encoder = encoder;
        this.clock = clock;
        this.lifetime = lifetime;
        this.issuer = issuer;
    }

    public TokenResponse issue(User user) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(user.getId().toString())
                .issuedAt(now).expiresAt(now.plus(lifetime))
                .claim("roles", List.of(user.getRole().name())).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        var token = encoder.encode(JwtEncoderParameters.from(header, claims));
        return new TokenResponse(token.getTokenValue(), "Bearer", lifetime.getSeconds());
    }
}
