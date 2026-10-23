package com.insurance.platform.auth;

import com.insurance.platform.auth.entity.*;
import com.insurance.platform.auth.enums.*;
import com.insurance.platform.auth.repository.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CustomerAdministrationTests {
    static final String PATH="/api/v1/admin/customers";
    static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final Instant NOW=Instant.parse("2026-10-19T01:02:03Z");
    @LocalServerPort int port;
    @MockitoBean UserRepository users;
    @MockitoBean com.insurance.platform.auth.repository.RefreshTokenRepository refreshTokens;
    @MockitoBean OutboxRepository outbox;
    @MockitoBean Clock clock;
    User user;
    final JsonMapper mapper=JsonMapper.builder().build();
    @BeforeEach void setup() {
        user=new User(ID,"customer@example.com","private-bcrypt-hash","First","Last",Role.CUSTOMER,UserStatus.ACTIVE,NOW.minusSeconds(60));
        when(clock.instant()).thenReturn(NOW);
        when(users.findById(ID)).thenReturn(Optional.of(user));when(users.findForUpdate(ID)).thenReturn(Optional.of(user));
        when(users.findByRole(eq(Role.CUSTOMER),any())).thenReturn(new PageImpl<>(List.of(user)));
    }
    HttpResponse<String> call(String method,String path,String body,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("X-Correlation-ID","x".repeat(128));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(body!=null)builder.header("Content-Type","application/json");
        try(var client=HttpClient.newHttpClient()) { return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()); }
    }
    HttpResponse<String> admin(String method,String suffix,String body) throws Exception { return call(method,PATH+suffix,body,AuthAdminJwtSupport.token("ADMIN")); }
    void safe(HttpResponse<String> response,int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).doesNotContain("password","passwordHash","private-bcrypt-hash","accessToken","refreshToken","Authorization","Bearer");
    }
    @Test void independentAuthAuthorizationForEveryEndpoint() throws Exception {
        for(String suffix:List.of("","/"+ID,"/"+ID+"/disable","/"+ID+"/enable")) {
            String method=suffix.endsWith("disable")||suffix.endsWith("enable")?"PATCH":"GET";
            safe(call(method,PATH+suffix,null,null),401);
            safe(call(method,PATH+suffix,null,AuthAdminJwtSupport.token("CUSTOMER")),403);
        }
        verifyNoInteractions(users,outbox);
    }
    @Test void safeListDetailPaginationAndReadOnlyBehavior() throws Exception {
        var response=admin("GET","",null);safe(response,200);
        assertThat(mapper.readTree(response.body()).path("size").asInt()).isEqualTo(20);
        var page=org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(users).findByRole(eq(Role.CUSTOMER),page.capture());
        assertThat(page.getValue().getSort()).containsExactly(Sort.Order.desc("createdAt"),Sort.Order.desc("id"));
        var detail=admin("GET","/"+ID,null);safe(detail,200);assertThat(mapper.readTree(detail.body()).size()).isEqualTo(6);
        for(String query:List.of("?page=-1","?size=0","?size=101","?page=bad"))safe(admin("GET",query,null),400);
        verifyNoInteractions(outbox);
    }
    @Test void statusTransitionsAreIdempotentAndEventsUseAuthenticatedActor() throws Exception {
        safe(admin("PATCH","/"+ID+"/disable",null),200);
        safe(admin("PATCH","/"+ID+"/disable","{}"),200);
        safe(admin("PATCH","/"+ID+"/enable",null),200);
        safe(admin("PATCH","/"+ID+"/enable",null),200);
        var captured=org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);verify(outbox,times(2)).saveAndFlush(captured.capture());verify(users,times(2)).flush();
        var events=captured.getAllValues();var ids=new HashSet<String>();
        for(int i=0;i<2;i++) {
            var event=mapper.readTree(events.get(i).getPayload());var d=event.path("data");
            assertThat(event.size()).isEqualTo(9);assertThat(d.size()).isEqualTo(6);
            assertThat(event.path("eventType").asText()).isEqualTo(i==0?"UserDisabled":"UserEnabled");
            assertThat(event.path("eventVersion").asInt()).isEqualTo(1);assertThat(event.path("aggregateType").asText()).isEqualTo("User");
            assertThat(event.path("aggregateId").asText()).isEqualTo(ID.toString());assertThat(event.path("producer").asText()).isEqualTo("auth-service");
            assertThat(event.path("correlationId").asText()).isEqualTo("x".repeat(128));assertThat(event.path("occurredAt").asText()).isEqualTo(NOW.toString());
            assertThat(d.path("changedAt").asText()).isEqualTo(NOW.toString());assertThat(d.path("changedBy").asText()).isEqualTo(AuthTestIdentity.ADMIN.toString());
            assertThat(d.path("email").asText()).isEqualTo(user.getEmail());assertThat(d.path("role").asText()).isEqualTo("CUSTOMER");
            assertThat(d.path("status").asText()).isEqualTo(i==0?"DISABLED":"ACTIVE");
            assertThat(events.get(i).getPayload()).doesNotContain("password","private-bcrypt-hash","accessToken","Authorization","Bearer");
            ids.add(event.path("eventId").asText());
        }
        assertThat(ids).hasSize(2);assertThat(user.getUpdatedAt()).isEqualTo(NOW);
    }
    @Test void invalidTargetsAndClientActorAreRejectedWithoutEvents() throws Exception {
        safe(admin("GET","/"+UUID.randomUUID(),null),404);
        safe(admin("PATCH","/"+UUID.randomUUID()+"/disable",null),404);
        var administrator=new User(ID,"admin@example.com","hash","Admin","User",Role.ADMIN,UserStatus.ACTIVE,NOW);
        when(users.findById(ID)).thenReturn(Optional.of(administrator));when(users.findForUpdate(ID)).thenReturn(Optional.of(administrator));
        safe(admin("GET","/"+ID,null),409);safe(admin("PATCH","/"+ID+"/disable",null),409);safe(admin("PATCH","/"+ID+"/enable",null),409);
        safe(admin("PATCH","/"+ID+"/disable","{\"changedBy\":\""+ID+"\"}"),400);
        safe(admin("GET","/not-a-uuid",null),400);
        verifyNoInteractions(outbox);verify(users,never()).flush();
    }
    @Test void invalidJwtAndInvalidAdminSubjectCannotMutate() throws Exception {
        var valid=AuthAdminJwtSupport.token("ADMIN");
        safe(call("PATCH",PATH+"/"+ID+"/disable",null,valid.substring(0,valid.length()-5)+"abcde"),401);
        safe(call("PATCH",PATH+"/"+ID+"/disable",null,AuthAdminJwtSupport.token("ADMIN","wrong-issuer",Instant.now().getEpochSecond()+300)),401);
        safe(call("PATCH",PATH+"/"+ID+"/disable",null,AuthAdminJwtSupport.token("ADMIN","insurance-auth-service",Instant.now().getEpochSecond()-10)),401);
        var malformed=AuthAdminJwtSupport.sign("{\"sub\":\"not-a-uuid\",\"roles\":[\"ADMIN\"],\"iss\":\"insurance-auth-service\",\"exp\":"+(Instant.now().getEpochSecond()+300)+"}");
        safe(call("PATCH",PATH+"/"+ID+"/disable",null,malformed),403);verifyNoInteractions(users,outbox);
    }

    @Test void failedStatusWriteCreatesNoEventAndLeaksNoDatabaseDetails() throws Exception {
        doThrow(new org.springframework.dao.DataIntegrityViolationException("private SQL payload")).when(users).flush();
        var response=admin("PATCH","/"+ID+"/disable",null);safe(response,500);
        assertThat(response.body()).doesNotContain("private SQL payload");verifyNoInteractions(outbox);
    }
    @Test void rejectsLegacyUnsignedWrongRsaAndMalformedTokensOverHttp() throws Exception {
        for (String rejected : RsaTestTokens.rejectedTokens()) {
            var response = call("GET",PATH,null,rejected);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).doesNotContain(rejected, "Exception", "PRIVATE KEY");
        }
    }

}
