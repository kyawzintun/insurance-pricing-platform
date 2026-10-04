package com.insurance.platform.auth.controller;

import com.insurance.platform.auth.dto.LoginRequest;
import com.insurance.platform.auth.dto.RegisterRequest;
import com.insurance.platform.auth.dto.TokenResponse;
import com.insurance.platform.auth.dto.UserResponse;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.insurance.platform.auth.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register")
    ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(request));
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .header("Pragma", "no-cache").body(auth.login(request));
    }
}
