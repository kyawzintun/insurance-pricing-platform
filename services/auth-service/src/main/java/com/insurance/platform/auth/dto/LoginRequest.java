package com.insurance.platform.auth.dto;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record LoginRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 72) String password) {
    public LoginRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    @JsonIgnore
    @AssertTrue(message = "Password must not exceed 72 UTF-8 bytes")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() { return "LoginRequest[redacted]"; }
}
