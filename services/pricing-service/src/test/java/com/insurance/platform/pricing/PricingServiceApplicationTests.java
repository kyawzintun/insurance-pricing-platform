package com.insurance.platform.pricing;

import com.insurance.platform.pricing.entity.PricingRule;
import com.insurance.platform.pricing.enums.*;
import com.insurance.platform.pricing.repository.PricingRuleRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PricingServiceApplicationTests {
    private static final Instant NOW=Instant.parse("2026-10-07T10:00:00Z");
    private static final String VALID="""
            {"dateOfBirth":"1995-04-20","drivingExperienceYears":8,"vehicleManufacturingYear":2021,
             "vehicleValue":700000,"engineSizeCc":1500,"previousClaimsCount":1,"coverageType":"COMPREHENSIVE"}
            """;
    @LocalServerPort int port;
    @MockitoBean com.insurance.platform.pricing.repository.OutboxRepository outbox;
    @MockitoBean PricingRuleRepository repository;
    @MockitoBean Clock clock;

    @BeforeEach void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(repository.findByEnabledTrueAndEffectiveFromLessThanEqual(NOW)).thenReturn(List.of(
                new PricingRule(new UUID(0,1),RuleType.BASE_PREMIUM,RuleOperator.EQUALS,null,null,null,new BigDecimal("8000"),NOW,true,"base"),
                new PricingRule(new UUID(0,2),RuleType.COVERAGE_TYPE,RuleOperator.EQUALS,"COMPREHENSIVE",null,new BigDecimal("1.4"),null,NOW,true,"coverage")));
    }
    private String serviceToken(String sub, String scope, String audience, long expiry) throws Exception {
        return RsaTestTokens.sign("{\"sub\":\""+sub+"\",\"iss\":\"insurance-auth-service\",\"token_use\":\"service\",\"scope\":[\""+scope+"\"],\"aud\":[\""+audience+"\"],\"exp\":"+expiry+"}");
    }
    private HttpResponse<String> post(String body) throws Exception {
        return postToken(serviceToken("quote-service", "pricing:calculate", "pricing-service", Instant.now().getEpochSecond()+300), body);
    }
    private HttpResponse<String> postToken(String token, String body) throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/pricing/calculate"))
                    .header("Authorization", token == null ? "Basic ignored" : "Bearer "+token)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void serviceIdentityScopeAudienceAndUserSeparation() throws Exception {
        long exp = Instant.now().getEpochSecond()+300;
        assertThat(postToken(null, VALID).statusCode()).isEqualTo(401);
        for (String[] claims : List.of(new String[]{"other", "pricing:calculate", "pricing-service"},
                new String[]{"quote-service", "other", "pricing-service"}, new String[]{"quote-service", "pricing:calculate", "other"})) {
            assertThat(postToken(serviceToken(claims[0],claims[1],claims[2],exp), VALID).statusCode()).isEqualTo(403);
        }
        for (String role : List.of("ADMIN", "CUSTOMER")) {
            String user = RsaTestTokens.sign("{\"sub\":\"00000000-0000-0000-0000-000000000001\",\"roles\":[\""+role+"\"],\"iss\":\"insurance-auth-service\",\"exp\":"+exp+"}");
            assertThat(postToken(user, VALID).statusCode()).isEqualTo(403);
        }
        assertThat(postToken(serviceToken("quote-service","pricing:calculate","pricing-service",1), VALID).statusCode()).isEqualTo(401);
        for (String token : RsaTestTokens.rejectedTokens()) assertThat(postToken(token, VALID).statusCode()).isEqualTo(401);
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/admin/pricing/rules"))
                    .header("Authorization", "Bearer "+serviceToken("quote-service","pricing:calculate","pricing-service",exp)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
        }
        verifyNoInteractions(repository);
    }
    @Test void healthRemainsPublic() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/actuator/health")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"");
        }
    }
    @Test void serviceAuthenticatedCalculationReturnsBreakdown() throws Exception {
        var response=post(VALID);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"finalPremium\":11200.00","\"currency\":\"THB\"",
                "\"calculatedAt\":\"2026-10-07T10:00:00Z\"","\"amountBefore\":8000.00","\"sequenceNumber\":1");
        assertThat(response.body()).doesNotContain("effectiveFrom","createdAt","enabled");
        verify(repository).findByEnabledTrueAndEffectiveFromLessThanEqual(NOW);
    }
    @ParameterizedTest @ValueSource(strings={
            "\"dateOfBirth\":null", "\"dateOfBirth\":\"2026-10-07\"", "\"dateOfBirth\":\"2027-01-01\"",
            "\"drivingExperienceYears\":-1", "\"drivingExperienceYears\":1.5", "\"coverageType\":0", "\"vehicleManufacturingYear\":1885", "\"vehicleManufacturingYear\":2027",
            "\"vehicleValue\":0", "\"vehicleValue\":-1", "\"vehicleValue\":1.123",
            "\"engineSizeCc\":0", "\"previousClaimsCount\":-1", "\"coverageType\":null", "\"coverageType\":\"OTHER\""})
    void invalidFieldsReturnSafe400(String replacement) throws Exception {
        var field=replacement.substring(0,replacement.indexOf(':'));
        var body=VALID.replaceAll(field+":(?:\"[^\"]*\"|[0-9]+)",replacement);
        var response=post(body);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo("{\"code\":\"INVALID_PRICING_REQUEST\",\"message\":\"Invalid pricing request\"}");
        verifyNoInteractions(repository);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{","null"})
    void malformedOrMissingInputReturns400(String body) throws Exception { assertThat(post(body).statusCode()).isEqualTo(400); }
    @Test void clientDerivedAgeAndIdentifiersAreRejected() throws Exception {
        for(var extra:List.of("\"driverAge\":20","\"customerId\":\"example\"","\"quoteId\":\"example\"")) {
            assertThat(post(VALID.replace("}",","+extra+"}")).statusCode()).isEqualTo(400);
        }
    }
    @Test void missingBaseReturnsSafeConfigurationError() throws Exception {
        when(repository.findByEnabledTrueAndEffectiveFromLessThanEqual(NOW)).thenReturn(List.of());
        var response=post(VALID);
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo("{\"code\":\"PRICING_CONFIGURATION_ERROR\",\"message\":\"Pricing configuration is unavailable or invalid\"}");
    }
    @Test void databaseErrorsDoNotExposeSqlDetails() throws Exception {
        when(repository.findByEnabledTrueAndEffectiveFromLessThanEqual(NOW)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL details"));
        var response=post(VALID);
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("PRICING_CONFIGURATION_ERROR").doesNotContain("private SQL details");
    }
}
