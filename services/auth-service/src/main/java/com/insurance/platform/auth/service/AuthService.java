package com.insurance.platform.auth.service;

import com.insurance.platform.auth.dto.LoginRequest;
import com.insurance.platform.auth.dto.RegisterRequest;
import com.insurance.platform.auth.dto.TokenResponse;
import com.insurance.platform.auth.dto.UserResponse;
import com.insurance.platform.auth.entity.OutboxEvent;
import com.insurance.platform.auth.entity.User;
import com.insurance.platform.auth.enums.Role;
import com.insurance.platform.auth.enums.UserStatus;
import com.insurance.platform.auth.event.AuthCorrelationId;
import com.insurance.platform.auth.event.EventEnvelope;
import com.insurance.platform.auth.event.UserRegisteredData;
import com.insurance.platform.auth.exception.AuthException;
import com.insurance.platform.auth.repository.OutboxRepository;
import com.insurance.platform.auth.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService tokens;
    private final Clock clock;
    private final String dummyHash;
    private final OutboxRepository outbox;
    private final AuthCorrelationId correlation;
    private final JsonMapper mapper;

    public AuthService(
            UserRepository users,
            PasswordEncoder passwords,
            JwtService tokens,
            Clock clock,
            OutboxRepository outbox,
            AuthCorrelationId correlation,
            JsonMapper mapper) {
        this.outbox = outbox;
        this.correlation = correlation;
        this.mapper = mapper;
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.clock = clock;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        var email = normalize(request.email());
        if (users.existsByEmailIgnoreCase(email)) {
            throw duplicateEmail();
        }
        var user = new User(
                UUID.randomUUID(),
                email,
                passwords.encode(request.password()),
                request.firstName().strip(),
                request.lastName().strip(),
                Role.CUSTOMER,
                UserStatus.ACTIVE,
                clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        );
        try {
            // Flush inside the transaction so concurrent duplicate registrations become a 409.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && "users_email_key".equals(violation.getConstraintName())) {
                    throw duplicateEmail();
                }
            }
            throw ex;
        }

        var event = new EventEnvelope<>(
                UUID.randomUUID(),
                "UserRegistered",
                1,
                "User",
                user.getId(),
                user.getCreatedAt(),
                correlation.current(),
                "auth-service",
                new UserRegisteredData(
                        user.getId(),
                        user.getEmail(),
                        user.getRole().name(),
                        user.getStatus().name(),
                        user.getCreatedAt()));

        outbox.saveAndFlush(new OutboxEvent(event, mapper.writeValueAsString(event)));
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        var user = users.findByEmailIgnoreCase(normalize(request.email()));
        // BCrypt work also occurs for unknown accounts; all failures use the same response.
        var valid = passwords.matches(request.password(), user.map(User::getPasswordHash).orElse(dummyHash));
        if (!valid || user.isEmpty() || user.get().getStatus() != UserStatus.ACTIVE) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials");
        }
        return tokens.issue(user.get());
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static AuthException duplicateEmail() {
        return new AuthException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Email is already registered");
    }
}
