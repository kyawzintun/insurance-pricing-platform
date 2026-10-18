package com.insurance.platform.auth.event;
import java.time.Instant;
import java.util.UUID;
public record UserRegisteredData(UUID userId, String email, String role, String status, Instant registeredAt) {}
