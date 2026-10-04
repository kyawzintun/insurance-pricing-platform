package com.insurance.platform.auth.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    @Override
    public String toString() { return "TokenResponse[redacted]"; }
}
