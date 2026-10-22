package com.insurance.platform.auth.service;

import com.insurance.platform.auth.dto.LoginRequest;
import com.insurance.platform.auth.dto.TokenResponse;
import com.insurance.platform.auth.entity.RefreshToken;
import com.insurance.platform.auth.enums.UserStatus;
import com.insurance.platform.auth.exception.AuthException;
import com.insurance.platform.auth.repository.RefreshTokenRepository;
import com.insurance.platform.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class RefreshSessionService {
    private final RefreshTokenRepository sessions;
    private final UserRepository users;
    private final AuthService auth;
    private final JwtService jwt;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public RefreshSessionService(RefreshTokenRepository sessions, UserRepository users, AuthService auth,
                                 JwtService jwt, Clock clock, @Value("${auth.refresh.ttl}") Duration ttl) {
        if (ttl.getSeconds() < 1 || ttl.getNano() != 0) {
            throw new IllegalArgumentException("Refresh TTL must be a positive whole number of seconds");
        }
        this.sessions = sessions;
        this.users = users;
        this.auth = auth;
        this.jwt = jwt;
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * Internal cookie delivery value; never serialized as an API response.
     */
    public static final class Session {
        public final TokenResponse access;
        public final String cookieToken;

        Session(TokenResponse access, String cookieToken) {
            this.access = access;
            this.cookieToken = cookieToken;
        }

        @Override
        public String toString() {
            return "Session[REDACTED]";
        }
    }

    @Transactional
    public Session login(LoginRequest request) {
        var access = auth.login(request);
        var user = users.findByEmailIgnoreCase(request.email().strip().toLowerCase(Locale.ROOT))
                .orElseThrow(RefreshSessionService::invalid);
        return new Session(access, create(user.getId()));
    }

    // A disabled-user rejection intentionally commits revocation. Infrastructure failures still roll back.
    @Transactional(noRollbackFor = AuthException.class)
    public Session refresh(String raw) {
        var old = sessions.findLocked(hashValid(raw)).orElseThrow(RefreshSessionService::invalid);
        var now = clock.instant();

        if (old.getRevokedAt() != null || !old.getExpiresAt().isAfter(now)) {
            throw invalid();
        }

        var user = users.findById(old.getUserId()).orElseThrow(RefreshSessionService::invalid);
        if (user.getStatus() != UserStatus.ACTIVE) {
            old.revoke(now);
            throw invalid();
        }
        
        var access = jwt.issue(user);
        old.revoke(now);
        return new Session(access, create(user.getId()));
    }

    @Transactional
    public void logout(String raw) {
        if (raw != null && raw.matches("[A-Za-z0-9_-]{43}")) {
            sessions.findLocked(hash(raw)).ifPresent(token -> token.revoke(clock.instant()));
        }
    }

    private String create(UUID user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        var raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var now = clock.instant();
        sessions.saveAndFlush(new RefreshToken(user, hash(raw), now, now.plus(ttl)));
        return raw;
    }

    private static String hashValid(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        return hash(raw);
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static AuthException invalid() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Authentication required");
    }
}
