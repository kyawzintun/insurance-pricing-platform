package com.insurance.platform.auth.service;

import com.insurance.platform.auth.dto.CustomerPage;
import com.insurance.platform.auth.dto.CustomerResponse;
import com.insurance.platform.auth.entity.OutboxEvent;
import com.insurance.platform.auth.entity.User;
import com.insurance.platform.auth.enums.Role;
import com.insurance.platform.auth.enums.UserStatus;
import com.insurance.platform.auth.event.AuthAdminIdentity;
import com.insurance.platform.auth.event.AuthCorrelationId;
import com.insurance.platform.auth.event.EventEnvelope;
import com.insurance.platform.auth.event.UserStatusEventData;
import com.insurance.platform.auth.exception.AuthException;
import com.insurance.platform.auth.repository.OutboxRepository;
import com.insurance.platform.auth.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CustomerAdminService {
    private final UserRepository users;
    private final OutboxRepository outbox;
    private final AuthAdminIdentity identity;
    private final AuthCorrelationId correlation;
    private final Clock clock;
    private final JsonMapper mapper;

    public CustomerAdminService(
            UserRepository users,
            OutboxRepository outbox,
            AuthAdminIdentity identity,
            AuthCorrelationId correlation,
            Clock clock,
            JsonMapper mapper) {
        this.users = users;
        this.outbox = outbox;
        this.identity = identity;
        this.correlation = correlation;
        this.clock = clock;
        this.mapper = mapper;
    }

    public CustomerPage list(int page, int size) {
        identity.current();
        if (page < 0 || size < 1 || size > 100) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid pagination");
        }

        var result = users.findByRole(Role.CUSTOMER, PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new CustomerPage(result.getContent().stream().map(CustomerResponse::from).toList(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    public CustomerResponse get(UUID id) {
        identity.current();
        return CustomerResponse.from(customer(users.findById(id).orElseThrow(CustomerAdminService::notFound)));
    }

    @Transactional
    public CustomerResponse setEnabled(UUID id, boolean enabled) {
        var admin = identity.current();
        // PostgreSQL holds this row lock through mutation + outbox commit. A waiter sees the committed status.
        var user = customer(users.findForUpdate(id).orElseThrow(CustomerAdminService::notFound));
        var target = enabled ? UserStatus.ACTIVE : UserStatus.DISABLED;
        if (user.getStatus() == target) return CustomerResponse.from(user);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        user.changeStatus(target, now);
        users.flush();
        var data = new UserStatusEventData(user.getId(), user.getEmail(), "CUSTOMER", target.name(), admin, now);
        var event = new EventEnvelope<>(UUID.randomUUID(), enabled ? "UserEnabled" : "UserDisabled", 1, "User", user.getId(), now,
                correlation.current(), "auth-service", data);
        outbox.saveAndFlush(new OutboxEvent(event, mapper.writeValueAsString(event)));
        return CustomerResponse.from(user);
    }

    private static User customer(User user) {
        if (user.getRole() != Role.CUSTOMER)
            throw new AuthException(HttpStatus.CONFLICT, "CUSTOMER_REQUIRED", "Only CUSTOMER accounts can be managed");
        return user;
    }

    private static AuthException notFound() {
        return new AuthException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found");
    }
}
