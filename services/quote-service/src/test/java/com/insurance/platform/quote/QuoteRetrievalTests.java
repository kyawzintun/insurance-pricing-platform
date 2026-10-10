package com.insurance.platform.quote;

import com.insurance.platform.quote.client.PricingClient;
import com.insurance.platform.quote.entity.*;
import com.insurance.platform.quote.enums.*;
import com.insurance.platform.quote.repository.*;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static com.insurance.platform.quote.QuoteFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class QuoteRetrievalTests {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.jwt.secret", QuoteJwtSupport::secret);
    }
    @LocalServerPort int port;
    @MockitoBean QuoteRepository quotes;
    @MockitoBean VehicleBrandRepository brands;
    @MockitoBean VehicleModelRepository models;
    @MockitoBean PricingClient pricing;
    private final UUID ownId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private final UUID otherCustomer = UUID.randomUUID();

    @BeforeEach void setup() {
        when(quotes.findByIdAndCustomerId(ownId, CUSTOMER)).thenReturn(Optional.of(snapshot(ownId, CUSTOMER)));
        when(quotes.findById(ownId)).thenReturn(Optional.of(snapshot(ownId, CUSTOMER)));
        when(quotes.findById(otherId)).thenReturn(Optional.of(snapshot(otherId, otherCustomer)));
    }
    @AfterEach void noExternalReadsOrWrites() {
        verifyNoInteractions(pricing, brands, models);
        verify(quotes, never()).saveAndFlush(any());
    }
    private static Quote snapshot(UUID id, UUID owner) {
        var quote = new Quote(id, "Q-historical-" + id, owner, QuoteStatus.PRICED, CoverageType.COMPREHENSIVE,
                price().finalPremium(), "THB", NOW, NOW, NOW.plusSeconds(2592000), NOW);
        var breakdown = new PricingBreakdown(UUID.randomUUID(), quote, price().basePremium(), price().finalPremium(), "THB", NOW);
        var adjustment = price().adjustments().getFirst();
        breakdown.addAdjustment(new PricingAdjustment(UUID.randomUUID(), breakdown, adjustment.pricingRuleId(),
                adjustment.ruleType(), "Historical adjustment", adjustment.inputValue(), adjustment.factor(),
                adjustment.amountBefore(), adjustment.amountAfter(), 1));
        quote.attachSnapshots(new QuoteDriver(UUID.randomUUID(), quote, request().dateOfBirth(), 8, 1),
                new QuoteVehicle(UUID.randomUUID(), quote, BRAND, "Historical Toyota", MODEL, "Historical Camry", 2021,
                        request().vehicleValue(), 1500), breakdown);
        return quote;
    }
    private HttpResponse<String> get(String path, String token) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/quotes" + path));
            if (token != null) builder.header("Authorization", "Bearer " + token);
            return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"").doesNotContain("customerId", "Exception", "SQL", "localhost");
    }
    @ParameterizedTest @ValueSource(strings = {"", "/00000000-0000-0000-0000-000000000001"})
    void noTokenIsUnauthorized(String path) throws Exception { error(get(path, null), 401, "UNAUTHORIZED"); }

    @ParameterizedTest @ValueSource(strings = {"malformed", "tampered", "expired", "issuer", "subject", "roles"})
    void invalidTokensAreUnauthorized(String kind) throws Exception {
        String token = switch (kind) {
            case "malformed" -> "not-a-jwt";
            case "tampered" -> QuoteJwtSupport.token("CUSTOMER").replaceFirst(".$", "!");
            case "expired" -> QuoteJwtSupport.token("CUSTOMER", "insurance-auth-service", Instant.now().getEpochSecond()-1);
            case "issuer" -> QuoteJwtSupport.token("CUSTOMER", "wrong", Instant.now().getEpochSecond()+300);
            case "subject" -> QuoteJwtSupport.sign("{\"sub\":\"invalid\",\"roles\":[\"CUSTOMER\"],\"iss\":\"insurance-auth-service\",\"exp\":"+(Instant.now().getEpochSecond()+300)+"}");
            default -> QuoteJwtSupport.sign("{\"sub\":\""+CUSTOMER+"\",\"roles\":[],\"iss\":\"insurance-auth-service\",\"exp\":"+(Instant.now().getEpochSecond()+300)+"}");
        };
        error(get("", token), 401, "UNAUTHORIZED");
        error(get("/"+ownId, token), 401, "UNAUTHORIZED");
        verifyNoInteractions(quotes);
    }
    @Test void unsupportedAuthorityIsForbidden() throws Exception {
        error(get("", QuoteJwtSupport.token("AUDITOR")), 403, "FORBIDDEN");
        error(get("/"+ownId, QuoteJwtSupport.token("AUDITOR")), 403, "FORBIDDEN");
        verifyNoInteractions(quotes);
    }
    @Test void customerRetrievesStoredSnapshotUsingOwnerQuery() throws Exception {
        var response = get("/"+ownId, QuoteJwtSupport.token("CUSTOMER"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Historical Toyota", "Historical Camry", "Historical adjustment", "11200.00", "\"sequenceNumber\":1");
        assertThat(response.body()).doesNotContain("customerId", "pricingRuleVersion");
        verify(quotes).findByIdAndCustomerId(ownId, CUSTOMER);
        verify(quotes, never()).findById(any());
    }
    @Test void crossCustomerAndMissingHaveIdentical404() throws Exception {
        var denied = get("/"+otherId, QuoteJwtSupport.token("CUSTOMER"));
        var missing = get("/"+UUID.randomUUID(), QuoteJwtSupport.token("CUSTOMER"));
        error(denied, 404, "QUOTE_NOT_FOUND");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).isEqualTo(denied.body());
        verify(quotes, never()).findById(any());
    }
    @Test void adminCanReadBothOwnersAndMissingIs404() throws Exception {
        var token = QuoteJwtSupport.token("ADMIN");
        assertThat(get("/"+ownId, token).statusCode()).isEqualTo(200);
        assertThat(get("/"+otherId, token).statusCode()).isEqualTo(200);
        error(get("/"+UUID.randomUUID(), token), 404, "QUOTE_NOT_FOUND");
        verify(quotes, never()).findByIdAndCustomerId(any(), any());
    }
    @Test void customerListUsesDefaultsAndOwnerScopeInBothQueries() throws Exception {
        var page = PageRequest.of(0, 20);
        when(quotes.findIdsByCustomerId(CUSTOMER, page)).thenReturn(new PageImpl<>(List.of(ownId), page, 1));
        when(quotes.findSnapshotsByIdsAndCustomerId(List.of(ownId), CUSTOMER)).thenReturn(List.of(snapshot(ownId, CUSTOMER)));
        var response = get("", QuoteJwtSupport.token("CUSTOMER"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(ownId.toString(), "\"page\":0", "\"size\":20", "\"totalElements\":1", "\"totalPages\":1").doesNotContain(otherId.toString());
        verify(quotes, never()).findIds(any());
        verify(quotes, never()).findSnapshotsByIds(any());
    }
    @Test void adminListRestoresIdPageOrderWhenFetchIsUnordered() throws Exception {
        var page = PageRequest.of(1, 2);
        when(quotes.findIds(page)).thenReturn(new PageImpl<>(List.of(otherId, ownId), page, 5));
        when(quotes.findSnapshotsByIds(List.of(otherId, ownId))).thenReturn(List.of(snapshot(ownId, CUSTOMER), snapshot(otherId, otherCustomer)));
        var response = get("?page=1&size=2", QuoteJwtSupport.token("ADMIN"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"totalElements\":5", "\"totalPages\":3", "\"page\":1");
        assertThat(response.body().indexOf(otherId.toString())).isLessThan(response.body().indexOf(ownId.toString()));
    }
    @Test void emptyPageDoesNotFetchSnapshots() throws Exception {
        var page = PageRequest.of(4, 20);
        when(quotes.findIdsByCustomerId(CUSTOMER, page)).thenReturn(new PageImpl<>(List.of(), page, 2));
        var response = get("?page=4", QuoteJwtSupport.token("CUSTOMER"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"content\":[]", "\"totalElements\":2");
        verify(quotes, never()).findSnapshotsByIdsAndCustomerId(any(), any());
    }
    @Test void maximumSizeIsAccepted() throws Exception {
        var page = PageRequest.of(0, 100);
        when(quotes.findIds(page)).thenReturn(new PageImpl<>(List.of(), page, 0));
        assertThat(get("?size=100", QuoteJwtSupport.token("ADMIN")).statusCode()).isEqualTo(200);
    }
    @ParameterizedTest @ValueSource(strings = {"page=-1", "page=x", "page=1.5", "page=", "size=0", "size=-1", "size=101", "size=x", "size=", "page=2147483647&size=100", "page=999999999999", "page=0&page=1", "size=1&size=2", "customerId=anything", "status=PRICED", "sort=id"})
    void invalidOrUnsupportedPageParametersAreRejected(String query) throws Exception {
        for (String role : List.of("CUSTOMER", "ADMIN")) error(get("?"+query, QuoteJwtSupport.token(role)), 400, "INVALID_PAGE_REQUEST");
        verifyNoInteractions(quotes);
    }
    @ParameterizedTest @ValueSource(strings = {"bad", "1-1-1-1-1"})
    void invalidQuoteIdsAreSafe(String id) throws Exception {
        error(get("/"+id, QuoteJwtSupport.token("CUSTOMER")), 400, "INVALID_QUOTE_REQUEST");
        verifyNoInteractions(quotes);
    }
    @ParameterizedTest @ValueSource(strings = {"FAILED", "EXPIRED"})
    void storedStatusIsReturnedWithoutTransitionOrRequiredSnapshots(String status) throws Exception {
        var quote = new Quote(ownId, "old-reference", CUSTOMER, QuoteStatus.valueOf(status), CoverageType.THIRD_PARTY,
                null, "THB", NOW, null, NOW.minusSeconds(1), NOW);
        when(quotes.findByIdAndCustomerId(ownId, CUSTOMER)).thenReturn(Optional.of(quote));
        var response = get("/"+ownId, QuoteJwtSupport.token("CUSTOMER"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\""+status+"\"", "\"driver\":null", "\"pricing\":null");
    }
}
