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
import org.springframework.data.domain.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PricingAdministrationTests {
    private static final String PATH = "/api/v1/admin/pricing/rules";
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final String VALID = """
            {"ruleType":"DRIVER_AGE","operator":"LESS_THAN","comparisonValue":"21",
             "comparisonValueTo":null,"factor":1.30,"fixedAmount":null,
             "effectiveFrom":"2026-11-01T00:00:00Z","enabled":true,"description":"Test rule"}
            """;
    @DynamicPropertySource static void jwt(DynamicPropertyRegistry properties) {
        properties.add("auth.jwt.secret", AdminJwtSupport::secret);
    }
    @LocalServerPort int port;
    @MockitoBean PricingRuleRepository repository;
    @MockitoBean Clock clock;
    private PricingRule stored;

    @BeforeEach void setup() {
        stored = new PricingRule(ID, RuleType.DRIVER_AGE, RuleOperator.LESS_THAN, "25", null,
                new BigDecimal("1.25"), null, NOW, false, "Existing rule");
        stored.initializeTimestamps(NOW.minusSeconds(60));
        when(clock.instant()).thenReturn(NOW);
        when(repository.findById(ID)).thenReturn(Optional.of(stored));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(repository.findRules(any(), any(), any())).thenReturn(new PageImpl<>(List.of(stored)));
    }
    private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (body != null) builder.header("Content-Type", "application/json");
            return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private HttpResponse<String> admin(String method, String path, String body) throws Exception {
        return request(method, path, body, AdminJwtSupport.token("ADMIN"));
    }
    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"").doesNotContain("Exception", "SQL", "stackTrace");
    }
    @ParameterizedTest @ValueSource(strings = {"GET", "POST", "PUT", "PATCH"})
    void noTokenIs401(String method) throws Exception {
        error(request(method, PATH + (method.equals("PATCH") ? "/" + ID + "/enable" : ""), VALID, null),401,"UNAUTHORIZED");
        verifyNoInteractions(repository);
    }
    @ParameterizedTest @ValueSource(strings = {"GET", "POST", "PUT", "PATCH"})
    void customerIs403(String method) throws Exception {
        error(request(method, PATH + (method.equals("PATCH") ? "/" + ID + "/disable" : ""), VALID,
                AdminJwtSupport.token("CUSTOMER")),403,"FORBIDDEN");
        verifyNoInteractions(repository);
    }
    @Test void tamperedJwtIs401() throws Exception {
        String jwt = AdminJwtSupport.token("ADMIN"); int start = jwt.lastIndexOf('.') + 1;
        jwt = jwt.substring(0,start) + (jwt.charAt(start)=='A'?'B':'A') + jwt.substring(start+1);
        error(request("GET",PATH,null,jwt),401,"UNAUTHORIZED");
    }
    @Test void expiredJwtIs401() throws Exception {
        error(request("GET",PATH,null,AdminJwtSupport.token("ADMIN","insurance-auth-service",Instant.now().getEpochSecond()-1)),401,"UNAUTHORIZED");
    }
    @Test void wrongIssuerIs401() throws Exception {
        error(request("GET",PATH,null,AdminJwtSupport.token("ADMIN","wrong",Instant.now().getEpochSecond()+300)),401,"UNAUTHORIZED");
    }
    @ParameterizedTest @ValueSource(strings={"\"sub\":\"\",\"roles\":[\"ADMIN\"]", "\"sub\":\"id\",\"roles\":[]", "\"sub\":\"id\",\"roles\":[1]"})
    void invalidClaimsAre401(String fields) throws Exception {
        error(request("GET",PATH,null,AdminJwtSupport.sign("{"+fields+",\"iss\":\"insurance-auth-service\",\"exp\":"+(Instant.now().getEpochSecond()+300)+"}")),401,"UNAUTHORIZED");
    }
    @Test void createGeneratesMetadataAndAllowsFutureEffectiveDate() throws Exception {
        var response=admin("POST",PATH,VALID);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location").orElseThrow()).startsWith(PATH+"/");
        assertThat(response.body()).contains("\"version\":0","\"createdAt\":\"2026-10-07T10:00:00Z\"",
                "\"updatedAt\":\"2026-10-07T10:00:00Z\"","2026-11-01T00:00:00Z");
        var captor=org.mockito.ArgumentCaptor.forClass(PricingRule.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getId()).isNotNull().isNotEqualTo(ID);
    }
    @ParameterizedTest @ValueSource(strings={"\"id\":\"00000000-0000-0000-0000-000000000001\"", "\"version\":0", "\"createdAt\":\"2026-01-01T00:00:00Z\"", "\"updatedAt\":\"2026-01-01T00:00:00Z\""})
    void serverFieldsCannotBeSuppliedOnCreate(String extra) throws Exception {
        error(admin("POST",PATH,VALID.replace("}",","+extra+"}")),400,"INVALID_PRICING_RULE");
        verifyNoInteractions(repository);
    }
    @ParameterizedTest @ValueSource(strings={
            "\"factor\":-1", "\"factor\":null", "\"factor\":1000000", "\"factor\":1.12345",
            "\"operator\":\"BETWEEN\"", "\"operator\":\"BAD\"", "\"comparisonValue\":\"bad\"",
            "\"comparisonValueTo\":\"30\"", "\"fixedAmount\":10", "\"enabled\":null",
            "\"ruleType\":null", "\"effectiveFrom\":null", "\"effectiveFrom\":\"bad\""})
    void invalidConfigurationReturns400(String replacement) throws Exception {
        String field=replacement.substring(0,replacement.indexOf(':'));
        String body=VALID.replaceAll(field+":(?:\"[^\"]*\"|null|true|[0-9.]+)",replacement);
        error(admin("POST",PATH,body),400,"INVALID_PRICING_RULE");
        verify(repository,never()).saveAndFlush(any());
    }
    @Test void invalidBaseBetweenAndCoverageReturn400() throws Exception {
        for (String body:List.of(
                VALID.replace("DRIVER_AGE","BASE_PREMIUM"),
                VALID.replace("LESS_THAN","BETWEEN").replace("\"comparisonValueTo\":null","\"comparisonValueTo\":\"20\""),
                VALID.replace("DRIVER_AGE","COVERAGE_TYPE").replace("\"21\"","\"COMPREHENSIVE\""),
                VALID.replace("DRIVER_AGE","COVERAGE_TYPE").replace("LESS_THAN","EQUALS"))) {
            error(admin("POST",PATH,body),400,"INVALID_PRICING_RULE");
        }
    }
    @Test void listSupportsPaginationAndFilters() throws Exception {
        when(repository.findRules(eq(RuleType.DRIVER_AGE),eq(false),any()))
                .thenReturn(new PageImpl<>(List.of(stored),PageRequest.of(0,2),5));
        var response=admin("GET",PATH+"?page=0&size=2&ruleType=DRIVER_AGE&enabled=false",null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"totalElements\":5","\"totalPages\":3","\"page\":0","\"size\":2");
        verify(repository).findRules(RuleType.DRIVER_AGE,false,PageRequest.of(0,2,Sort.by("id")));
    }
    @ParameterizedTest @ValueSource(strings={"?page=-1","?size=0","?size=101","?ruleType=BAD","?enabled=BAD","/bad-uuid"})
    void invalidListAndIdInputsReturn400(String suffix) throws Exception {
        error(admin("GET",PATH+suffix,null),400,"INVALID_PRICING_RULE");
    }
    @Test void getAndMissing() throws Exception {
        assertThat(admin("GET",PATH+"/"+ID,null).statusCode()).isEqualTo(200);
        error(admin("GET",PATH+"/"+UUID.randomUUID(),null),404,"PRICING_RULE_NOT_FOUND");
    }
    @Test void validUpdateChangesFieldsButPreservesIdentityAndCreationTime() throws Exception {
        var response=admin("PUT",PATH+"/"+ID,VALID.replace("}",",\"version\":0}"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(stored.getId()).isEqualTo(ID);
        assertThat(stored.getCreatedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
        assertThat(stored.getComparisonValue()).isEqualTo("21");
        verify(repository).flush();
    }
    @Test void updateValidationStillApplies() throws Exception {
        error(admin("PUT",PATH+"/"+ID,VALID.replace("1.30","-1").replace("}",",\"version\":0}")),400,"INVALID_PRICING_RULE");
        verify(repository,never()).flush();
    }
    @Test void staleUpdateConflictsBeforeModification() throws Exception {
        error(admin("PUT",PATH+"/"+ID,VALID.replace("}",",\"version\":1}")),409,"PRICING_RULE_VERSION_CONFLICT");
        assertThat(stored.getComparisonValue()).isEqualTo("25");
        verify(repository,never()).flush();
    }
    @ParameterizedTest @ValueSource(strings={"enable","disable"})
    void togglesRequireVersionAndApplyState(String operation) throws Exception {
        error(admin("PATCH",PATH+"/"+ID+"/"+operation,"{}"),400,"INVALID_PRICING_RULE");
        error(admin("PATCH",PATH+"/"+ID+"/"+operation,"{\"version\":1}"),409,"PRICING_RULE_VERSION_CONFLICT");
        assertThat(admin("PATCH",PATH+"/"+ID+"/"+operation,"{\"version\":0}").statusCode()).isEqualTo(200);
        assertThat(stored.isEnabled()).isEqualTo(operation.equals("enable"));
        assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
    }
    @Test void concurrentJpaConflictHasSafe409() throws Exception {
        doThrow(new ObjectOptimisticLockingFailureException(PricingRule.class,ID)).when(repository).flush();
        var response=admin("PATCH",PATH+"/"+ID+"/enable","{\"version\":0}");
        error(response,409,"PRICING_RULE_VERSION_CONFLICT");
        assertThat(response.body()).contains("Pricing rule was modified by another request");
    }
}
