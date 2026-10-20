package com.insurance.platform.auth.event;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class AuthAdminIdentity {
    public UUID current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()
                || jwt.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new AccessDeniedException("Authenticated ADMIN is required");
        }

        try {
            return UUID.fromString(jwt.getToken().getSubject());
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new AccessDeniedException("ADMIN subject must be a UUID");
        }
    }
}
