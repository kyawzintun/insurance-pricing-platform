package com.insurance.platform.auth.controller;

import com.insurance.platform.auth.dto.LoginRequest;
import com.insurance.platform.auth.dto.RegisterRequest;
import com.insurance.platform.auth.dto.TokenResponse;
import com.insurance.platform.auth.dto.UserResponse;
import com.insurance.platform.auth.service.AuthService;
import com.insurance.platform.auth.service.RefreshSessionService.Session;
import com.insurance.platform.auth.service.RefreshSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final RefreshSessionService sessions;
    private final String cookieName;
    private final boolean secure;
    private final String sameSite;
    private final Duration ttl;

    public AuthController(AuthService auth, RefreshSessionService sessions,
                          @Value("${auth.refresh.cookie-name}") String cookieName,
                          @Value("${auth.refresh.secure}") boolean secure,
                          @Value("${auth.refresh.same-site}") String sameSite,
                          @Value("${auth.refresh.ttl}") Duration ttl) {
        this.auth = auth;
        this.sessions = sessions;
        this.cookieName = cookieName;
        this.secure = secure;
        this.sameSite = sameSite;
        this.ttl = ttl;
        if (!List.of("Lax", "Strict").contains(sameSite))
            throw new IllegalArgumentException("SameSite must be Lax or Strict");
    }

    private String cookie(String raw, Duration age) {
        return ResponseCookie.from(cookieName, raw).httpOnly(true).secure(secure)
                .sameSite(sameSite).path("/api/v1/auth").maxAge(age).build().toString();
    }

    private String token(HttpServletRequest request) {
        if (request.getCookies() != null)
            for (var c : request.getCookies()) if (c.getName().equals(cookieName)) return c.getValue();
        return null;
    }

    private ResponseEntity<TokenResponse> response(Session session) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache")
                .header("Set-Cookie", cookie(session.cookieToken, ttl)).body(session.access);
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        return response(sessions.refresh(token(request)));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.logout(token(request));
        return ResponseEntity.noContent().header("Cache-Control", "no-store")
                .header("Set-Cookie", cookie("", Duration.ZERO)).build();
    }

    @PostMapping("/register")
    ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(request));
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return response(sessions.login(request));
    }
}
