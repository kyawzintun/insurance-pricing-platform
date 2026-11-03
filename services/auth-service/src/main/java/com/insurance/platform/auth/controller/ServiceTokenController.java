package com.insurance.platform.auth.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Focused machine credential exchange; deliberately independent of user refresh sessions.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class ServiceTokenController {
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String clientId, secret, issuer;
    private final Duration ttl;

    public ServiceTokenController(JwtEncoder encoder, Clock clock,
                                  @Value("${auth.service-client.id:quote-service}") String clientId,
                                  @Value("${auth.service-client.secret:}") String secret,
                                  @Value("${auth.service-client.token-ttl:5m}") Duration ttl,
                                  @Value("${auth.jwt.issuer}") String issuer) {
        if (ttl.getSeconds() < 1 || ttl.getNano() != 0) {
            throw new IllegalArgumentException("Invalid service token TTL");
        }
        this.encoder = encoder;
        this.clock = clock;
        this.clientId = clientId;
        this.secret = secret;
        this.ttl = ttl;
        this.issuer = issuer;
    }

    @PostMapping(value = "/service-token", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<?> token(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestParam(value = "grant_type", required = false) String grant) {
        boolean valid = false;
        try {
            if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
                String credentials = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
                int colon = credentials.indexOf(':');
                if (colon > 0) {
                    boolean idMatches = MessageDigest.isEqual(clientId.getBytes(StandardCharsets.UTF_8), credentials.substring(0, colon).getBytes(StandardCharsets.UTF_8));
                    boolean secretMatches = MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), credentials.substring(colon + 1).getBytes(StandardCharsets.UTF_8));
                    valid = !secret.isBlank() && idMatches && secretMatches;
                }
            }
        } catch (IllegalArgumentException ignored) { /* Do not expose credential material. */ }

        if (!valid) {
            return ResponseEntity
                    .status(401)
                    .header("WWW-Authenticate", "Basic realm=service-token")
                    .header("Cache-Control", "no-store")
                    .body(Map.of("error", "invalid_client"));
        }

        if (!"client_credentials".equals(grant)) {
            return ResponseEntity
                    .badRequest()
                    .header("Cache-Control", "no-store")
                    .body(Map.of("error", "unsupported_grant_type"));
        }
        var now = clock.instant();
        var claims = JwtClaimsSet
                .builder()
                .issuer(issuer)
                .subject(clientId)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .audience(List.of("pricing-service"))
                .claim("token_use", "service")
                .claim("scope", List.of("pricing:calculate"))
                .build();
        var token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build(), claims));

        return ResponseEntity
                .ok()
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .body(Map.of("access_token", token.getTokenValue(), "token_type", "Bearer", "expires_in", ttl.getSeconds()));
    }
}
