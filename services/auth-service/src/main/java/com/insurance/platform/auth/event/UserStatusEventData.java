package com.insurance.platform.auth.event;
import java.time.Instant;
import java.util.UUID;
public record UserStatusEventData(
        UUID userId,
        String email,
        String role,
        String status,
        UUID changedBy,
        Instant changedAt) {}
