package com.insurance.platform.quote;

import com.insurance.platform.quote.entity.*;
import com.insurance.platform.quote.enums.QuoteStatus;
import com.insurance.platform.quote.repository.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static com.insurance.platform.quote.QuoteFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class QuoteServiceApplicationTests {
    private static final HttpServer PRICING;
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static volatile String downstreamBody;
    private static volatile String downstreamAuthorization;
    private static volatile int downstreamStatus;
    private static volatile String downstreamResponse;
    private static volatile long delay;
    static {
        try {
            PRICING=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PRICING.setExecutor(EXECUTOR);
            PRICING.createContext("/internal/v1/pricing/calculate", exchange -> {
                downstreamBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                downstreamAuthorization=exchange.getRequestHeaders().getFirst("Authorization");
                int status=downstreamStatus; byte[] body=downstreamResponse.getBytes(StandardCharsets.UTF_8); long wait=delay;
                if(wait>0) try { Thread.sleep(wait); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
                try {
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(status,body.length);
                    exchange.getResponseBody().write(body);
                } finally { exchange.close(); }
            });
            PRICING.start();
        } catch(Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.jwt.secret",QuoteJwtSupport::secret);
        registry.add("pricing.service.url",() -> "http://127.0.0.1:"+PRICING.getAddress().getPort());
        registry.add("pricing.service.read-timeout",() -> "500ms");
    }
    @AfterAll static void stop() { PRICING.stop(0); EXECUTOR.shutdownNow(); }
    @LocalServerPort int port;
    @MockitoBean VehicleBrandRepository brands;
    @MockitoBean VehicleModelRepository models;
    @MockitoBean QuoteRepository quotes;
    @MockitoBean Clock clock;
    @BeforeEach void setup() {
        downstreamBody=null; downstreamAuthorization=null; downstreamStatus=200; downstreamResponse=PRICE; delay=0;
        when(clock.instant()).thenReturn(NOW);
        when(brands.findById(BRAND)).thenReturn(Optional.of(new VehicleBrand(BRAND,"Toyota from database",true,NOW)));
        when(models.findById(MODEL)).thenReturn(Optional.of(new VehicleModel(MODEL,BRAND,"Camry from database",true,NOW)));
        when(quotes.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }
    private HttpResponse<String> request(String method,String path,String body,String token) throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
            if(token!=null) builder.header("Authorization","Bearer "+token);
            if(body!=null) builder.header("Content-Type","application/json");
            return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    private HttpResponse<String> post(String body) throws Exception { return request("POST","/api/v1/quotes",body,QuoteJwtSupport.token("CUSTOMER")); }
    private void error(HttpResponse<String> response,int status,String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":\""+code+"\"").doesNotContain("Exception","localhost","SQL","private downstream");
        verifyNoInteractions(quotes);
    }
    @Test void healthIsPublic() throws Exception {
        assertThat(request("GET","/actuator/health",null,null).statusCode()).isEqualTo(200);
    }
    @Test void missingJwtIs401() throws Exception { error(request("POST","/api/v1/quotes",INPUT,null),401,"UNAUTHORIZED"); }
    @Test void adminOnlyIs403() throws Exception { error(request("POST","/api/v1/quotes",INPUT,QuoteJwtSupport.token("ADMIN")),403,"FORBIDDEN"); }
    @Test void tamperedJwtIs401() throws Exception {
        String jwt=QuoteJwtSupport.token("CUSTOMER"); int at=jwt.lastIndexOf('.')+1;
        jwt=jwt.substring(0,at)+(jwt.charAt(at)=='A'?'B':'A')+jwt.substring(at+1);
        error(request("POST","/api/v1/quotes",INPUT,jwt),401,"UNAUTHORIZED");
    }
    @Test void expiredJwtIs401() throws Exception {
        error(request("POST","/api/v1/quotes",INPUT,QuoteJwtSupport.token("CUSTOMER","insurance-auth-service",Instant.now().getEpochSecond()-1)),401,"UNAUTHORIZED");
    }
    @Test void wrongIssuerIs401() throws Exception {
        error(request("POST","/api/v1/quotes",INPUT,QuoteJwtSupport.token("CUSTOMER","wrong",Instant.now().getEpochSecond()+300)),401,"UNAUTHORIZED");
    }
    @Test void nonUuidSubjectIs401() throws Exception {
        String token=QuoteJwtSupport.sign("{\"sub\":\"not-a-uuid\",\"roles\":[\"CUSTOMER\"],\"iss\":\"insurance-auth-service\",\"exp\":"+(Instant.now().getEpochSecond()+300)+"}");
        error(request("POST","/api/v1/quotes",INPUT,token),401,"UNAUTHORIZED");
    }
    @Test void completeQuoteUsesJwtIdentityAndDatabaseVehicleNames() throws Exception {
        var response=post(INPUT);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"status\":\"PRICED\"","Toyota from database","Camry from database","\"finalPremium\":11200.00","2026-11-07T10:00:00Z");
        assertThat(response.body()).doesNotContain("customerId","pricingRuleVersion","basePricingRuleId");
        var captor=ArgumentCaptor.forClass(Quote.class); verify(quotes).saveAndFlush(captor.capture());
        var quote=captor.getValue();
        assertThat(quote.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(quote.getStatus()).isEqualTo(QuoteStatus.PRICED);
        assertThat(quote.getQuoteReference()).matches("Q-20261008-[0-9A-F]{32}").hasSize(43);
        assertThat(Duration.between(quote.getPricedAt(),quote.getExpiresAt())).isEqualTo(Duration.ofDays(30));
        assertThat(quote.getDriver().getDateOfBirth()).isEqualTo(LocalDate.of(1995,4,20));
        assertThat(quote.getDriver().getQuote()).isSameAs(quote);
        assertThat(quote.getVehicle().getBrandId()).isEqualTo(BRAND);
        assertThat(quote.getVehicle().getBrandName()).isEqualTo("Toyota from database");
        assertThat(quote.getPricing().getAdjustments()).singleElement().satisfies(a -> {
            assertThat(a.getSequenceNumber()).isEqualTo(1); assertThat(a.getPricingBreakdown()).isSameAs(quote.getPricing());
        });
        assertThat(downstreamBody).contains("\"dateOfBirth\":\"1995-04-20\"","\"drivingExperienceYears\":8",
                "\"vehicleManufacturingYear\":2021","\"vehicleValue\":700000.00","\"engineSizeCc\":1500","\"previousClaimsCount\":1","\"coverageType\":\"COMPREHENSIVE\"")
                .doesNotContain("customerId","vehicleBrandId","vehicleModelId","quoteId");
        assertThat(downstreamAuthorization).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"\"dateOfBirth\":null","\"dateOfBirth\":\"2026-10-08\"","\"dateOfBirth\":\"2027-01-01\"",
            "\"drivingExperienceYears\":-1","\"drivingExperienceYears\":1.5","\"previousClaimsCount\":-1",
            "\"vehicleManufacturingYear\":1885","\"vehicleManufacturingYear\":2027","\"vehicleValue\":0","\"vehicleValue\":-1",
            "\"vehicleValue\":10000000000000","\"vehicleValue\":1.123","\"engineSizeCc\":0","\"coverageType\":\"OTHER\"",
            "\"coverageType\":0","\"vehicleBrandId\":null","\"vehicleModelId\":null"})
    void invalidInput(String replacement) throws Exception {
        String field=replacement.substring(0,replacement.indexOf(':'));
        error(post(INPUT.replaceAll(field+":(?:\"[^\"]*\"|[0-9.]+)",replacement)),400,"INVALID_QUOTE_REQUEST");
        verifyNoInteractions(brands,models); assertThat(downstreamBody).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"\"customerId\":\"00000000-0000-0000-0000-000000000002\"","\"quoteId\":\"example\"",
            "\"status\":\"PRICED\"","\"premium\":1","\"pricing\":{}","\"createdAt\":\"2026-01-01T00:00:00Z\"",
            "\"expiresAt\":\"2026-01-01T00:00:00Z\"","\"brandName\":\"Fake\"","\"modelName\":\"Fake\""})
    void serverOwnedFieldsCannotBeSupplied(String field) throws Exception {
        error(post(INPUT.replace("}",","+field+"}")),400,"INVALID_QUOTE_REQUEST");
    }
    @ParameterizedTest @ValueSource(strings={"{}","{","null"})
    void malformedInput(String body) throws Exception { error(post(body),400,"INVALID_QUOTE_REQUEST"); }
    @Test void unknownBrand() throws Exception { when(brands.findById(BRAND)).thenReturn(Optional.empty()); error(post(INPUT),404,"VEHICLE_BRAND_NOT_FOUND"); }
    @Test void unknownModel() throws Exception { when(models.findById(MODEL)).thenReturn(Optional.empty()); error(post(INPUT),404,"VEHICLE_MODEL_NOT_FOUND"); }
    @Test void inactiveBrand() throws Exception {
        when(brands.findById(BRAND)).thenReturn(Optional.of(new VehicleBrand(BRAND,"Toyota",false,NOW)));
        error(post(INPUT),400,"INVALID_VEHICLE_SELECTION"); assertThat(downstreamBody).isNull();
    }
    @Test void inactiveModel() throws Exception {
        when(models.findById(MODEL)).thenReturn(Optional.of(new VehicleModel(MODEL,BRAND,"Camry",false,NOW)));
        error(post(INPUT),400,"INVALID_VEHICLE_SELECTION"); assertThat(downstreamBody).isNull();
    }
    @Test void wrongBrandForModel() throws Exception {
        when(models.findById(MODEL)).thenReturn(Optional.of(new VehicleModel(MODEL,UUID.randomUUID(),"Camry",true,NOW)));
        error(post(INPUT),400,"INVALID_VEHICLE_SELECTION"); assertThat(downstreamBody).isNull();
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={400,401,403,500,503})
    void downstreamErrorsAreSafe(int status) throws Exception {
        downstreamStatus=status; downstreamResponse="{\"private downstream\":\"SQL details\"}";
        error(post(INPUT),502,"PRICING_SERVICE_ERROR");
    }
    @Test void downstreamTimeoutIs503AndDoesNotPersist() throws Exception {
        delay=1200; error(post(INPUT),503,"PRICING_SERVICE_UNAVAILABLE");
    }
    @ParameterizedTest @ValueSource(strings={"{","{}","null"})
    void malformedPricingResponse(String body) throws Exception { downstreamResponse=body; error(post(INPUT),502,"PRICING_SERVICE_ERROR"); }
    @ParameterizedTest @ValueSource(strings={"\"currency\":\"USD\"","\"finalPremium\":null","\"finalPremium\":-1",
            "\"finalPremium\":11200.001","\"factor\":-1","\"amountBefore\":7000.00","\"sequenceNumber\":2","\"ruleType\":\"BASE_PREMIUM\""})
    void invalidPricingSnapshot(String replacement) throws Exception {
        String field=replacement.substring(0,replacement.indexOf(':'));
        downstreamResponse=PRICE.replaceAll(field+":(?:\"[^\"]*\"|[0-9.]+)",replacement);
        error(post(INPUT),502,"PRICING_SERVICE_ERROR");
    }
    @Test void snapshotAdjustmentsAreStoredInSequenceOrder() throws Exception {
        downstreamResponse=PRICE.replace("11200.00","14000.00").replace("\"amountBefore\":8000.00","\"amountBefore\":10000.00")
                .replace("\"sequenceNumber\":1", "\"sequenceNumber\":2")
                .replace("]}", ",{\"pricingRuleId\":\"1e506e1d-0cdd-583f-83ae-1d591a9ff53d\",\"pricingRuleVersion\":0,\"ruleType\":\"DRIVER_AGE\",\"inputValue\":\"23\",\"factor\":1.25,\"amountBefore\":8000.00,\"amountAfter\":10000.00,\"sequenceNumber\":1}]}");
        assertThat(post(INPUT).statusCode()).isEqualTo(201);
        var captor=ArgumentCaptor.forClass(Quote.class);verify(quotes).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPricing().getAdjustments()).extracting(PricingAdjustment::getSequenceNumber).containsExactly(1,2);
    }
}
