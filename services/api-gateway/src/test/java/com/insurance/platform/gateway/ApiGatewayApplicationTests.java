package com.insurance.platform.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiGatewayApplicationTests {
    private static final byte[] KEY = new byte[32];
    private static final HttpServer DOWNSTREAM;
    private static volatile String forwardedAuthorization;
    private static volatile String forwardedCorrelation;
    private static volatile String forwardedBody;
    static {
        new SecureRandom().nextBytes(KEY);
        try {
            DOWNSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            DOWNSTREAM.createContext("/api/v1/auth/", exchange -> {
                forwardedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                forwardedCorrelation = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
                forwardedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] body = "{\"downstream\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("register") ? 201 : 200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            DOWNSTREAM.start();
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.jwt.secret", () -> Base64.getEncoder().encodeToString(KEY));
        registry.add("AUTH_SERVICE_URL", () -> "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort());
    }

    @AfterAll
    static void stopDownstream() { DOWNSTREAM.stop(0); }

    @LocalServerPort int port;

    private HttpResponse<String> request(String method, String path, String... headers) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (headers.length > 0) builder.headers(headers);
            return client.send(builder.method(method, method.equals("POST")
                    ? HttpRequest.BodyPublishers.ofString("{\"example\":true}")
                    : HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String token(String claims) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String data = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        return data + "." + encoder.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private String claims(String issuer, long expiry) {
        return "{\"sub\":\"00000000-0000-0000-0000-000000000001\",\"roles\":[\"CUSTOMER\"],\"iss\":\""
                + issuer + "\",\"iat\":" + (Instant.now().getEpochSecond() - 60) + ",\"exp\":" + expiry + "}";
    }

    private void assertUnauthorized(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo("{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication is required\"}");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(response.headers().firstValue("X-Correlation-ID")).isPresent();
    }

    @Test void registrationIsPublicAndBodyIsForwarded() throws Exception {
        assertThat(request("POST", "/api/v1/auth/register", "Content-Type", "application/json").statusCode()).isEqualTo(201);
        assertThat(forwardedBody).isEqualTo("{\"example\":true}");
        assertThat(forwardedAuthorization).isNull();
    }
    @Test void loginIsPublic() throws Exception {
        assertThat(request("POST", "/api/v1/auth/login").statusCode()).isEqualTo(200);
    }
    @ParameterizedTest @ValueSource(strings = {"/actuator/health", "/actuator/info"})
    void operationalEndpointsArePublic(String path) throws Exception {
        assertThat(request("GET", path).statusCode()).isEqualTo(200);
    }
    @ParameterizedTest @ValueSource(strings = {"/api/v1/auth/probe", "/api/v1/auth/register", "/unmapped"})
    void otherRequestsRequireAuthentication(String path) throws Exception { assertUnauthorized(request("GET", path)); }

    @Test void validJwtAndCorrelationAreForwardedUnchanged() throws Exception {
        String bearer = "Bearer " + token(claims("insurance-auth-service", Instant.now().getEpochSecond() + 300));
        var response = request("GET", "/api/v1/auth/probe", "Authorization", bearer, "X-Correlation-ID", "test-request-123");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("downstream");
        assertThat(forwardedAuthorization).isEqualTo(bearer);
        assertThat(forwardedCorrelation).isEqualTo("test-request-123");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("test-request-123");
    }
    @Test void expiredTokenIsRejectedWithoutClockSkew() throws Exception {
        assertUnauthorized(request("GET", "/api/v1/auth/probe", "Authorization", "Bearer "
                + token(claims("insurance-auth-service", Instant.now().getEpochSecond() - 1))));
    }
    @Test void wrongIssuerIsRejected() throws Exception {
        assertUnauthorized(request("GET", "/api/v1/auth/probe", "Authorization", "Bearer "
                + token(claims("other-issuer", Instant.now().getEpochSecond() + 300))));
    }
    @Test void tamperedSignatureIsRejected() throws Exception {
        String jwt = token(claims("insurance-auth-service", Instant.now().getEpochSecond() + 300));
        int start = jwt.lastIndexOf('.') + 1;
        jwt = jwt.substring(0, start) + (jwt.charAt(start) == 'A' ? 'B' : 'A') + jwt.substring(start + 1);
        assertUnauthorized(request("GET", "/api/v1/auth/probe", "Authorization", "Bearer " + jwt));
    }
    @ParameterizedTest @ValueSource(strings = {"Bearer", "Bearer nonsense", "Bearer a.b.c", "Basic abc", "Bearer a b"})
    void malformedAuthorizationIsRejected(String header) throws Exception {
        assertUnauthorized(request("GET", "/api/v1/auth/probe", "Authorization", header));
    }
    @ParameterizedTest @ValueSource(strings = {"exp", "sub", "roles"})
    void requiredClaimsCannotBeMissing(String claim) throws Exception {
        String json = claims("insurance-auth-service", Instant.now().getEpochSecond() + 300);
        json = json.replaceAll("\\\"" + claim + "\\\":(\\[[^]]*\\]|\\\"[^\\\"]*\\\"|[0-9]+),?", "").replace(",}", "}");
        assertUnauthorized(request("GET", "/api/v1/auth/probe", "Authorization", "Bearer " + token(json)));
    }
    @Test void invalidRolesAreRejected() throws Exception {
        String json = claims("insurance-auth-service", Instant.now().getEpochSecond() + 300).replace("[\"CUSTOMER\"]", "[123]");
        assertUnauthorized(request("GET", "/api/v1/auth/probe", "Authorization", "Bearer " + token(json)));
    }
    @Test void invalidSuppliedTokenIsRejectedEvenOnPublicEndpoint() throws Exception {
        assertUnauthorized(request("POST", "/api/v1/auth/login", "Authorization", "Bearer invalid"));
    }
    @Test void allowedCorsPreflightNeedsNoToken() throws Exception {
        var response = request("OPTIONS", "/api/v1/auth/login", "Origin", "http://localhost:4200",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "authorization,content-type");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:4200");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }
    @Test void unapprovedCorsOriginIsRejected() throws Exception {
        assertThat(request("OPTIONS", "/api/v1/auth/login", "Origin", "http://unapproved.example",
                "Access-Control-Request-Method", "POST").statusCode()).isEqualTo(403);
    }
    @Test void unsafeCorrelationIsReplaced() throws Exception {
        var response = request("GET", "/actuator/health", "X-Correlation-ID", "invalid value");
        assertThat(UUID.fromString(response.headers().firstValue("X-Correlation-ID").orElseThrow())).isNotNull();
    }
}
