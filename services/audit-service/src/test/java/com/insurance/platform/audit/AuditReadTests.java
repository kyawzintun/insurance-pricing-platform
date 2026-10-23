package com.insurance.platform.audit;

import com.insurance.platform.audit.entity.AuditRecord;
import com.insurance.platform.audit.repository.*;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuditReadTests {
    @MockitoBean AuditRecordRepository repository;
    @MockitoBean ProcessedEventRepository processed;
    @LocalServerPort int port;
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000022");
    @BeforeEach void setup() {
        var row = new AuditRecord(ID,UUID.randomUUID(),ID.toString(),ID.toString(),"trace-22",Instant.EPOCH,
            "quote-service","{\"quoteId\":\"<script>alert(1)</script>\"}",Instant.EPOCH);
        when(repository.findAll(any(Specification.class),any(Pageable.class))).thenAnswer(i -> new PageImpl<>(List.of(row),i.getArgument(1),1));
        when(repository.findById(ID)).thenReturn(Optional.of(row));
    }
    private String token(String role) throws Exception {
        var e = Base64.getUrlEncoder().withoutPadding();
        String claims = "{\"sub\":\"admin\",\"roles\":[\""+role+"\"],\"iss\":\"insurance-auth-service\",\"exp\":"+(Instant.now().getEpochSecond()+300)+"}";
        return RsaTestTokens.sign(claims);
    }

    private HttpResponse<String> request(String method,String path,String role) throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/admin/audit"+path)).method(method,HttpRequest.BodyPublishers.noBody());
            if(role!=null)b.header("Authorization","Bearer "+token(role));
            return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void independentAuthorization() throws Exception {
        for(String path:List.of("","/"+ID)) {
            assertThat(request("GET",path,null).statusCode()).isEqualTo(401);
            assertThat(request("GET",path,"CUSTOMER").statusCode()).isEqualTo(403);
        }
        verify(repository,never()).findById(any());
    }
    @Test void listDefaultsCompactDtoAndDeterministicSort() throws Exception {
        var response=request("GET","","ADMIN");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"page\":0","\"size\":20","trace-22").doesNotContain("metadata","script");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var captor=org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class),captor.capture());
        assertThat(captor.getValue().getSort().toString()).isEqualTo("occurredAt: DESC,id: DESC");
    }
    @Test void detailReturnsStoredJsonAndSafeNotFound() throws Exception {
        var response=request("GET","/"+ID,"ADMIN");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"metadata\":{","<script>alert(1)</script>").doesNotContain("password","headers");
        assertThat(request("GET","/"+UUID.randomUUID(),"ADMIN").statusCode()).isEqualTo(404);
    }
    @Test void rejectsInvalidQueries() throws Exception {
        for(String query:List.of("?page=-1","?page=2147483647&size=100","?size=0","?size=101","?page=no","?actorId=no","?entityId=no","?eventId=no","?from=no","?from=2026-10-21T00:00:00Z&to=2026-10-20T00:00:00Z","/bad-id")) {
            var response=request("GET",query,"ADMIN");
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(response.body()).doesNotContain("SQL","Exception","select ");
        }
        verify(repository,never()).findAll(any(Specification.class),any(Pageable.class));
    }
    @Test void mutationMethodsDenied() throws Exception {
        for(String method:List.of("POST","PUT","PATCH","DELETE")) assertThat(request(method,"/"+ID,"ADMIN").statusCode()).isEqualTo(403);
        verify(repository,never()).save(any());verify(repository,never()).deleteById(any());
    }
    @Test void rejectsLegacyUnsignedWrongRsaAndMalformedTokensOverHttp() throws Exception {
        for (String rejected : RsaTestTokens.rejectedTokens()) {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/admin/audit"))
                        .header("Authorization","Bearer "+rejected).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).doesNotContain(rejected, "Exception", "PRIVATE KEY");
        }
    }

}
