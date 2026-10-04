package com.insurance.platform.auth.dto;

import com.insurance.platform.auth.entity.User;
import com.insurance.platform.auth.enums.Role;
import com.insurance.platform.auth.enums.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, String firstName, String lastName,
                           Role role, UserStatus status, Instant createdAt) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFirstName(),
                user.getLastName(), user.getRole(), user.getStatus(), user.getCreatedAt());
    }
}
