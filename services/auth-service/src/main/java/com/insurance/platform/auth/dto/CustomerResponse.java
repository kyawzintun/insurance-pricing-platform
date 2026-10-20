package com.insurance.platform.auth.dto;

import com.insurance.platform.auth.entity.User;
import com.insurance.platform.auth.enums.Role;
import com.insurance.platform.auth.enums.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe admin view; never serialize User entities.
 */
public record CustomerResponse(
        UUID id,
        String email,
        Role role,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt) {
    public static CustomerResponse from(User user) {
        return new CustomerResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
