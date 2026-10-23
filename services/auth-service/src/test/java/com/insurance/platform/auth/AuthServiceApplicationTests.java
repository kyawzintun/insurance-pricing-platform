package com.insurance.platform.auth;

import com.insurance.platform.auth.entity.User;
import com.insurance.platform.auth.enums.Role;
import com.insurance.platform.auth.enums.UserStatus;
import com.insurance.platform.auth.repository.UserRepository;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthServiceApplicationTests {
    private static final String PASSWORD = "Test-only-password-42!";

    @MockitoBean com.insurance.platform.auth.repository.OutboxRepository outbox;
    @MockitoBean UserRepository users;
    @MockitoBean com.insurance.platform.auth.repository.RefreshTokenRepository refreshTokens;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    @Test
    void refreshFailuresAreSafeAndLogoutAlwaysClearsCookie() throws Exception {
        for(var raw:List.of("malformed", "A".repeat(43))) {
          var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/refresh"))
            .header("Cookie","refresh_token="+raw).POST(HttpRequest.BodyPublishers.noBody()).build();
          var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
          assertError(response,401,"INVALID_REFRESH_TOKEN");assertThat(response.body()).doesNotContain(raw);
        }
        for(int i=0;i<2;i++) {
          var response=post("logout",Map.of());
          assertThat(response.statusCode()).isEqualTo(204);
          assertThat(response.headers().firstValue("Set-Cookie")).get().asString()
            .contains("Max-Age=0","Path=/api/v1/auth","HttpOnly","Secure","SameSite=Lax");
        }
    }

    @Test
    void contextStartsAndHealthIsAvailable() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/info").statusCode()).isEqualTo(200);
    }

    @Test
    void registrationNormalizesEmailHashesPasswordAndAssignsCustomer() throws Exception {
        var response = post("register", registration("  CUSTOMER@Example.COM  "));
        assertThat(response.statusCode()).isEqualTo(201);
        var body = mapper.readTree(response.body());
        assertThat(body.get("email").asText()).isEqualTo("customer@example.com");
        assertThat(body.get("firstName").asText()).isEqualTo("Kyaw");
        assertThat(body.get("role").asText()).isEqualTo("CUSTOMER");
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(response.body()).doesNotContain(PASSWORD, "password", "hash");
        var captured = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(captured.capture());
        var user = captured.getValue();
        assertThat(user.getId()).isNotNull();
        assertThat(user.getPasswordHash()).startsWith("$2a$12$");
        assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(user.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(users).existsByEmailIgnoreCase("customer@example.com");
    }

    @Test
    void duplicateCanonicalEmailIsRejected() throws Exception {
        when(users.existsByEmailIgnoreCase("customer@example.com")).thenReturn(true);
        var response = post("register", registration(" CUSTOMER@EXAMPLE.COM "));
        assertError(response, 409, "EMAIL_ALREADY_EXISTS");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateConstraintBecomesConflict() throws Exception {
        var violation = new org.hibernate.exception.ConstraintViolationException(
                "duplicate", new SQLException("duplicate", "23505"), "users_email_key");
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate", violation));
        assertError(post("register", registration("customer@example.com")), 409, "EMAIL_ALREADY_EXISTS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "status"})
    void publicRegistrationRejectsPrivilegeFields(String field) throws Exception {
        var request = registration("customer@example.com");
        request.put(field, field.equals("role") ? "ADMIN" : "DISABLED");
        assertError(post("register", request), 400, "INVALID_REQUEST");
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "password", "firstName", "lastName"})
    void requiredFieldsAreValidated(String field) throws Exception {
        var request = registration("customer@example.com");
        request.put(field, " ");
        assertError(post("register", request), 400, "INVALID_REQUEST");
        verifyNoInteractions(users);
    }

    @Test
    void invalidEmailAndShortAndMultibyteOversizedPasswordsAreRejected() throws Exception {
        assertError(post("register", registration("not-an-email")), 400, "INVALID_REQUEST");
        for (var password : List.of("short", "a".repeat(73), "é".repeat(37))) {
            var request = registration("customer@example.com");
            request.put("password", password);
            assertError(post("register", request), 400, "INVALID_REQUEST");
        }
        verifyNoInteractions(users);
    }

    @Test
    void loginReturnsSignedTokenWithExpectedClaimsAndDefaultLifetime() throws Exception {
        var user = user(Role.CUSTOMER, UserStatus.ACTIVE);
        when(users.findByEmailIgnoreCase("customer@example.com")).thenReturn(Optional.of(user));
        var response = post("login", Map.of("email", " CUSTOMER@EXAMPLE.COM ", "password", PASSWORD));
        var cookie=response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("HttpOnly","Secure","SameSite=Lax","Path=/api/v1/auth","Max-Age=604800");
        var raw=cookie.split(";",2)[0].split("=",2)[1];
        var captured=ArgumentCaptor.forClass(com.insurance.platform.auth.entity.RefreshToken.class);
        verify(refreshTokens).saveAndFlush(captured.capture());
        assertThat(captured.getValue().getTokenHash()).hasSize(64).isNotEqualTo(raw);
        assertThat(response.body()).doesNotContain(raw,"refreshToken");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var body = mapper.readTree(response.body());
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        var decoder = NimbusJwtDecoder.withPublicKey(com.insurance.platform.auth.config.RsaKeys.publicKey(new org.springframework.core.io.ClassPathResource("keys/dev-jwt-public.pem"))).build();
        var jwt = decoder.decode(body.get("accessToken").asText());
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("sub", "roles", "iat", "exp", "iss");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("insurance-auth-service");
    }

    @Test
    void wrongPasswordUnknownEmailAndDisabledUserHaveIdenticalErrors() throws Exception {
        var request = Map.of("email", "customer@example.com", "password", PASSWORD);
        when(users.findByEmailIgnoreCase("customer@example.com")).thenReturn(Optional.empty());
        var unknown = post("login", request);
        assertError(unknown, 401, "INVALID_CREDENTIALS");
        when(users.findByEmailIgnoreCase("customer@example.com")).thenReturn(Optional.of(user(Role.CUSTOMER, UserStatus.DISABLED)));
        var disabled = post("login", request);
        assertError(disabled, 401, "INVALID_CREDENTIALS");
        when(users.findByEmailIgnoreCase("customer@example.com")).thenReturn(Optional.of(user(Role.CUSTOMER, UserStatus.ACTIVE)));
        var wrong = post("login", Map.of("email", "customer@example.com", "password", "Wrong-password-42!"));
        assertError(wrong, 401, "INVALID_CREDENTIALS");
        assertThat(wrong.body()).isEqualTo(unknown.body()).isEqualTo(disabled.body());
    }

    @Test
    void otherEndpointsAndWrongHttpMethodsAreProtected() throws Exception {
        assertError(get("/api/v1/auth/register"), 401, "UNAUTHORIZED");
        assertError(get("/api/v1/auth/login"), 401, "UNAUTHORIZED");
        assertError(get("/actuator/env"), 401, "UNAUTHORIZED");
        assertError(get("/private"), 401, "UNAUTHORIZED");
    }

    @Test
    void malformedJsonDoesNotExposeRequestOrStackTrace() throws Exception {
        var response = send("POST", "/api/v1/auth/login", "{\"password\":\"do-not-leak\",");
        assertError(response, 400, "INVALID_REQUEST");
        assertThat(response.body()).doesNotContain("do-not-leak", "exception", "trace");
    }

    @Test
    void unsupportedContentTypeIsAClientError() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("not-json")).build();
            assertError(client.send(request, HttpResponse.BodyHandlers.ofString()), 400, "INVALID_REQUEST");
        }
    }

    private User user(Role role, UserStatus status) {
        return new User(UUID.randomUUID(), "customer@example.com", passwords.encode(PASSWORD),
                "Kyaw", "Tun", role, status, Instant.now());
    }
    private Map<String, String> registration(String email) {
        return new HashMap<>(Map.of("email", email, "password", PASSWORD, "firstName", " Kyaw ", "lastName", " Tun "));
    }
    private HttpResponse<String> post(String operation, Map<String, String> request) throws Exception {
        return send("POST", "/api/v1/auth/" + operation, mapper.writeValueAsString(request));
    }
    private HttpResponse<String> get(String path) throws Exception { return send("GET", path, ""); }
    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(mapper.readTree(response.body()).get("code").asText()).isEqualTo(code);
    }
}
