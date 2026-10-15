package com.insurance.platform.quote;

import com.insurance.platform.quote.client.PricingClient;
import com.insurance.platform.quote.dto.VehicleSnapshot;
import com.insurance.platform.quote.exception.QuoteException;
import com.insurance.platform.quote.service.QuoteService;
import com.insurance.platform.quote.service.QuotePersistenceService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.insurance.platform.quote.QuoteFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in disposable PostgreSQL only: -Ppostgres-it verify. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@Testcontainers
class QuotePostgresIT {
    @Container static final PostgreSQLContainer DATABASE=new PostgreSQLContainer("postgres:17.11-bookworm");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",DATABASE::getUsername);
        registry.add("spring.datasource.password",DATABASE::getPassword);
        registry.add("auth.jwt.secret",QuoteJwtSupport::secret);
        registry.add("outbox.publisher.enabled", () -> "false");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        registry.add("spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch", () -> "true");
    }
    @Autowired com.insurance.platform.quote.service.QuoteRetrievalService retrieval;
    @Autowired com.insurance.platform.quote.repository.QuoteRepository repository;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @Autowired QuoteService service;
    @Autowired QuotePersistenceService persistence;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PricingClient pricing;
    @MockitoBean Clock clock;
    @MockitoBean org.springframework.kafka.core.KafkaTemplate<String, String> kafka;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @BeforeEach void setup() {
        // This class uses only a disposable Testcontainers database.
        jdbc.update("delete from pricing_adjustments");
        jdbc.update("delete from pricing_breakdowns");
        jdbc.update("delete from quote_drivers");
        jdbc.update("delete from quote_vehicles");
        jdbc.update("delete from quotes");
        jdbc.update("delete from outbox_events");
        when(clock.instant()).thenReturn(NOW);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(new java.util.concurrent.CompletableFuture<>());
        when(pricing.calculate(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return price();
        });
    }
    private Map<String,Long> counts() {
        var values=new LinkedHashMap<String,Long>();
        for(String table:List.of("quotes","quote_drivers","quote_vehicles","pricing_breakdowns","pricing_adjustments","outbox_events")) {
            values.put(table,jdbc.queryForObject("select count(*) from "+table,Long.class));
        }
        return values;
    }
    @Test void completeGraphPersistsWithExactSnapshotsAndThirtyDays() {
        var customer=UUID.randomUUID();
        var response=service.create(customer,request());
        var quote=jdbc.queryForMap("select * from quotes where id=?",response.id());
        assertThat(quote.get("customer_id")).isEqualTo(customer);
        assertThat(quote.get("status")).isEqualTo("PRICED");
        assertThat((BigDecimal)quote.get("premium_amount")).isEqualTo(new BigDecimal("11200.00"));
        assertThat(response.quoteReference()).hasSize(43);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(jdbc.queryForObject("select extract(epoch from expires_at-priced_at) from quotes where id=?",BigDecimal.class,response.id()))
                .isEqualByComparingTo("2592000");
        var driver=jdbc.queryForMap("select * from quote_drivers where quote_id=?",response.id());
        assertThat(driver.get("date_of_birth").toString()).isEqualTo("1995-04-20");
        assertThat(driver.get("driving_experience_years")).isEqualTo(8);
        assertThat(driver.get("previous_claims_count")).isEqualTo(1);
        var vehicle=jdbc.queryForMap("select * from quote_vehicles where quote_id=?",response.id());
        assertThat(vehicle.get("brand_id")).isEqualTo(BRAND);
        assertThat(vehicle.get("model_id")).isEqualTo(MODEL);
        assertThat(vehicle.get("brand_name")).isEqualTo("Toyota");
        assertThat(vehicle.get("model_name")).isEqualTo("Camry");
        assertThat((BigDecimal)vehicle.get("vehicle_value")).isEqualTo(new BigDecimal("700000.00"));
        var breakdown=jdbc.queryForMap("select * from pricing_breakdowns where quote_id=?",response.id());
        assertThat((BigDecimal)breakdown.get("base_premium")).isEqualTo(new BigDecimal("8000.00"));
        assertThat(breakdown.get("calculated_at").toString()).contains("2026-10-08");
        var adjustments=jdbc.queryForList("select * from pricing_adjustments where pricing_breakdown_id=? order by sequence_number",breakdown.get("id"));
        assertThat(adjustments).hasSize(1);
        assertThat(adjustments.getFirst().get("pricing_rule_id")).isEqualTo(price().adjustments().getFirst().pricingRuleId());
        assertThat(adjustments.getFirst().get("sequence_number")).isEqualTo(1);
        assertThat((BigDecimal)adjustments.getFirst().get("factor")).isEqualTo(new BigDecimal("1.4000"));
        assertThat(jdbc.queryForObject("select count(*) from outbox_events",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success",Long.class)).isEqualTo(6);
        // Existing unique quote reference and one-driver-per-quote constraints are real PostgreSQL constraints.
        assertThatThrownBy(() -> jdbc.update("insert into quotes select ?,quote_reference,customer_id,status,coverage_type,premium_amount,currency,created_at,priced_at,expires_at,updated_at from quotes where id=?",UUID.randomUUID(),response.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into quote_drivers select ?,quote_id,date_of_birth,driving_experience_years,previous_claims_count from quote_drivers where quote_id=?",UUID.randomUUID(),response.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void childDatabaseFailureRollsBackEntireAggregate() {
        var before=counts();
        var invalidVehicle=new VehicleSnapshot(BRAND,"X".repeat(101),MODEL,"Camry",2021,new BigDecimal("700000"),1500);
        assertThatThrownBy(() -> persistence.create(UUID.randomUUID(),request(),invalidVehicle,price()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(counts()).isEqualTo(before);
        verifyNoInteractions(kafka);
    }
    @Test void pricingFailureLeavesNoRows() {
        var before=counts();
        when(pricing.calculate(any())).thenThrow(QuoteException.pricingUnavailable());
        assertThatThrownBy(() -> service.create(UUID.randomUUID(),request())).isInstanceOf(QuoteException.class);
        assertThat(counts()).isEqualTo(before);
        verifyNoInteractions(kafka);
    }
    @Test void sequentialQuotesHaveUniqueReferencesAndIndependentSnapshots() {
        var first=service.create(UUID.randomUUID(),request());
        var second=service.create(UUID.randomUUID(),request());
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.quoteReference()).isNotEqualTo(second.quoteReference());
        var changed=new com.insurance.platform.quote.client.PricingResult(price().basePricingRuleId(),1L,new BigDecimal("9000.00"),new BigDecimal("9000.00"),"THB",NOW,List.of());
        when(pricing.calculate(any())).thenReturn(changed);
        var third=service.create(UUID.randomUUID(),request());
        assertThat(third.pricing().finalPremium()).isEqualByComparingTo("9000");
        assertThat(jdbc.queryForObject("select premium_amount from quotes where id=?",BigDecimal.class,first.id())).isEqualByComparingTo("11200");
    }

    private Map<String, String> contents() {
        var result = new LinkedHashMap<String, String>();
        for (String table : List.of("quotes", "quote_drivers", "quote_vehicles", "pricing_breakdowns",
                "pricing_adjustments", "outbox_events", "vehicle_brands", "vehicle_models", "flyway_schema_history")) {
            result.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text, '[]') from " + table + " t", String.class));
        }
        return result;
    }

    @Test void ownershipPaginationAndGraphFetchHaveBoundedQueriesAndNoWrites() {
        var customerA = UUID.randomUUID();
        var customerB = UUID.randomUUID();
        var a1 = service.create(customerA, request());
        var a2 = service.create(customerA, request());
        var b = service.create(customerB, request());
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        var newest = service.create(customerA, request());
        // Multiple adjustment rows must not duplicate root quotes or change page counts.
        jdbc.update("insert into pricing_adjustments select ?, pricing_breakdown_id, pricing_rule_id, rule_type, description, input_value, factor, amount_after, amount_after, 2 from pricing_adjustments where pricing_breakdown_id = (select id from pricing_breakdowns where quote_id=?)", UUID.randomUUID(), a1.id());
        var before = contents();
        clearInvocations(pricing);
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();
        var firstPage = retrieval.list(customerA, false, 0, 2);
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        var tiedIds = List.of(a1.id(), a2.id()).stream().sorted(Comparator.comparing(UUID::toString).reversed()).toList();
        assertThat(firstPage.content()).extracting(com.insurance.platform.quote.dto.QuoteResponse::id).containsExactly(newest.id(), tiedIds.getFirst());
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(retrieval.list(customerA, false, 1, 2).content()).extracting(com.insurance.platform.quote.dto.QuoteResponse::id).containsExactly(tiedIds.getLast());
        assertThat(retrieval.list(customerB, false, 0, 20).content()).extracting(com.insurance.platform.quote.dto.QuoteResponse::id).containsExactly(b.id());
        assertThat(repository.findByIdAndCustomerId(b.id(), customerA)).isEmpty();
        assertThatThrownBy(() -> retrieval.get(b.id(), customerA, false)).isInstanceOfSatisfying(QuoteException.class,
                ex -> assertThat(ex.code()).isEqualTo("QUOTE_NOT_FOUND"));
        statistics.clear();
        var admin = retrieval.list(customerA, true, 0, 2);
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        assertThat(admin.totalElements()).isEqualTo(4);
        assertThat(admin.totalPages()).isEqualTo(2);
        assertThat(admin.content()).hasSize(2);
        var adminAll = retrieval.list(customerA, true, 0, 100);
        assertThat(adminAll.content()).extracting(com.insurance.platform.quote.dto.QuoteResponse::id)
                .containsExactlyInAnyOrder(a1.id(), a2.id(), b.id(), newest.id());
        statistics.clear();
        var loaded = retrieval.get(a1.id(), customerA, false);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(loaded.driver().dateOfBirth()).isEqualTo(request().dateOfBirth());
        assertThat(loaded.vehicle().brandName()).isEqualTo("Toyota");
        assertThat(loaded.pricing().adjustments()).extracting(com.insurance.platform.quote.dto.QuoteResponse.Adjustment::sequenceNumber).containsExactly(1, 2);
        assertThat(retrieval.get(b.id(), customerA, true).id()).isEqualTo(b.id());
        assertThat(retrieval.list(customerA, false, 20, 2).content()).isEmpty();
        assertThat(contents()).isEqualTo(before);
        verifyNoInteractions(pricing);
    }

    @Test void currentCatalogAndPricingChangesDoNotAffectStoredSnapshots() {
        var owner = UUID.randomUUID();
        var original = service.create(owner, request());
        jdbc.update("update vehicle_brands set name='Changed catalog brand' where id=?", BRAND);
        jdbc.update("update vehicle_models set name='Changed catalog model' where id=?", MODEL);
        try {
            when(pricing.calculate(any())).thenThrow(new AssertionError("Retrieval must not call Pricing"));
            clearInvocations(pricing);
            var before = contents();
            assertThat(retrieval.get(original.id(), owner, false)).isEqualTo(original);
            assertThat(retrieval.list(owner, false, 0, 20).content()).containsExactly(original);
            assertThat(contents()).isEqualTo(before);
            verifyNoInteractions(pricing);
        } finally {
            jdbc.update("update vehicle_brands set name='Toyota' where id=?", BRAND);
            jdbc.update("update vehicle_models set name='Camry' where id=?", MODEL);
        }
    }

    @Test void storedExpiredAndFailedStatusesAreReadWithoutTransitions() {
        var owner = UUID.randomUUID();
        var original = service.create(owner, request());
        jdbc.update("update quotes set status='EXPIRED' where id=?", original.id());
        assertThat(retrieval.get(original.id(), owner, false).status()).isEqualTo(com.insurance.platform.quote.enums.QuoteStatus.EXPIRED);
        var failed = UUID.randomUUID();
        jdbc.update("insert into quotes (id,quote_reference,customer_id,status,coverage_type,premium_amount,currency,created_at,expires_at,updated_at) values (?,?,?,'FAILED','THIRD_PARTY',0,'THB',now(),now(),now())", failed, "Q-"+failed, owner);
        var before = contents();
        var response = retrieval.get(failed, owner, false);
        assertThat(response.status()).isEqualTo(com.insurance.platform.quote.enums.QuoteStatus.FAILED);
        assertThat(response.driver()).isNull();
        assertThat(response.pricing()).isNull();
        assertThat(retrieval.list(owner, false, 0, 20).content()).hasSize(2);
        assertThat(contents()).isEqualTo(before);
    }

    @Autowired com.insurance.platform.quote.service.VehicleReferenceService catalog;
    @Test void catalogFiltersActiveRowsByBrandAndDoesNotWrite() {
        var active=UUID.randomUUID(); var inactive=UUID.randomUUID();
        var activeModel=UUID.randomUUID(); var hiddenModel=UUID.randomUUID(); var otherModel=UUID.randomUUID();
        jdbc.update("insert into vehicle_brands (id,name,active,created_at) values (?, 'Test Active', true, now()), (?, 'Test Inactive', false, now())",active,inactive);
        jdbc.update("insert into vehicle_models (id,brand_id,name,active,created_at) values (?,?,'Visible',true,now()), (?,?,'Hidden',false,now()), (?,?,'Other',true,now())",activeModel,active,hiddenModel,active,otherModel,inactive);
        try {
            var before=contents();
            assertThat(catalog.brands()).extracting(com.insurance.platform.quote.dto.VehicleReferenceResponse::id).contains(active).doesNotContain(inactive);
            assertThat(catalog.models(active)).extracting(com.insurance.platform.quote.dto.VehicleReferenceResponse::id).containsExactly(activeModel);
            assertThatThrownBy(() -> catalog.models(inactive)).isInstanceOf(QuoteException.class);
            assertThatThrownBy(() -> catalog.models(UUID.randomUUID())).isInstanceOf(QuoteException.class);
            assertThat(contents()).isEqualTo(before);
        } finally {
            jdbc.update("delete from vehicle_models where brand_id in (?,?)",active,inactive);
            jdbc.update("delete from vehicle_brands where id in (?,?)",active,inactive);
        }
    }

    @Test void quoteAndOutboxCommitTogetherWithoutCallingKafka() {
        var owner = UUID.randomUUID();
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        var result = tx.execute(status -> {
            var quote = persistInTransaction(owner);
            assertThat(jdbc.queryForObject("select count(*) from quotes where id=?", Long.class, quote.id())).isEqualTo(1);
            verifyNoInteractions(kafka); // saveAndFlush happened, but commit has not.
            return quote;
        });
        verifyNoInteractions(kafka);
        assertThat(jdbc.queryForObject("select status from outbox_events where aggregate_id=?", String.class, result.id().toString())).isEqualTo("PENDING");
        clearInvocations(kafka);
        tx.executeWithoutResult(status -> {
            persistInTransaction(owner);
            verifyNoInteractions(kafka);
            status.setRollbackOnly();
        });
        verifyNoInteractions(kafka);
        assertThat(jdbc.queryForObject("select count(*) from quotes where customer_id=?", Long.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events", Long.class)).isEqualTo(1);
    }
    @Test void actualCommitFailurePublishesNothing() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        var before = counts();
        var reachedCommit = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            persistInTransaction(UUID.randomUUID());
            verifyNoInteractions(kafka);
            // Deferred PostgreSQL FK fails at commit, after the quote and outbox were created.
            jdbc.execute("CREATE TABLE phase12_commit_failure (quote_id uuid REFERENCES quotes(id) DEFERRABLE INITIALLY DEFERRED)");
            jdbc.update("INSERT INTO phase12_commit_failure VALUES (?)", UUID.randomUUID());
            reachedCommit.set(true);
        })).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
        assertThat(reachedCommit).isTrue();
        verifyNoInteractions(kafka);
        assertThat(counts()).isEqualTo(before);
    }
    @Test void brokerFailureAfterCommitStillReturns201AndKeepsPricedQuote() throws Exception {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("test broker unavailable")));
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/v1/quotes"))
                    .header("Authorization", "Bearer "+QuoteJwtSupport.token("CUSTOMER"))
                    .header("Content-Type", "application/json").header("X-Correlation-ID", "phase12-http-failure")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(INPUT)).build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
            var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
            var id = UUID.fromString(body.get("id").asText());
            assertThat(jdbc.queryForObject("select status from quotes where id=?", String.class, id)).isEqualTo("PRICED");
            assertThat(jdbc.queryForObject("select count(*) from pricing_breakdowns where quote_id=?", Long.class, id)).isEqualTo(1);
            verifyNoInteractions(kafka);
            assertThat(jdbc.queryForObject("select correlation_id from outbox_events where aggregate_id=?", String.class, id.toString())).isEqualTo("phase12-http-failure");
        }
    }
    @Test void synchronousKafkaEnqueueFailureCannotRollBackQuote() {
        when(kafka.send(anyString(), anyString(), any())).thenThrow(new IllegalStateException("test enqueue failure"));
        var quote = service.create(UUID.randomUUID(), request());
        assertThat(jdbc.queryForObject("select status from quotes where id=?", String.class, quote.id())).isEqualTo("PRICED");
        verifyNoInteractions(kafka);
    }
    @Autowired javax.sql.DataSource dataSource;

    @Test void exactEnvelopeAnd128CharacterCorrelationArePersistedOnce() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", "c".repeat(128));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            var quote = service.create(CUSTOMER, request());
            var row = jdbc.queryForMap("select * from outbox_events");
            var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(row.get("payload").toString());
            assertThat(row.get("event_id").toString()).isEqualTo(json.get("eventId").asText());
            assertThat(row.get("aggregate_id")).isEqualTo(quote.id().toString());
            assertThat(row.get("aggregate_type")).isEqualTo("Quote");
            assertThat(row.get("event_type")).isEqualTo("QuotePriced");
            assertThat(row.get("event_version")).isEqualTo(1);
            assertThat(row.get("correlation_id")).isEqualTo("c".repeat(128));
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("retry_count")).isEqualTo(0);
            assertThat(row.get("published_at")).isNull();
            assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(NOW);
            assertThat(json.get("occurredAt").asText()).isEqualTo(NOW.toString());
            assertThat(json.get("correlationId").asText()).isEqualTo("c".repeat(128));
            verifyNoInteractions(kafka);
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }

    @Test void outboxInsertFailureRollsBackEveryQuoteTable() {
        var before = counts();
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT phase16_fail CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(() -> service.create(CUSTOMER, request())).isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(failure -> assertThat(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                            .doesNotContain("Failing row contains"));
            assertThat(counts()).isEqualTo(before);
            verifyNoInteractions(kafka);
        } finally { jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT phase16_fail"); }
    }

    @Test void childFailureAfterOutboxInsertRollsBackBoth() {
        var before = counts();
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            var quote = persistInTransaction(CUSTOMER);
            assertThat(jdbc.queryForObject("select count(*) from outbox_events", Long.class)).isEqualTo(1);
            jdbc.update("update quote_vehicles set brand_name=? where quote_id=?", "x".repeat(101), quote.id());
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(counts()).isEqualTo(before);
    }

    @Test void pollingIsBoundedOrderedExclusiveAndDoesNotHoldAnOpenTransaction() throws Exception {
        service.create(CUSTOMER, request());
        when(clock.instant()).thenReturn(NOW.plusSeconds(1)); service.create(CUSTOMER, request());
        when(clock.instant()).thenReturn(NOW.plusSeconds(2)); service.create(CUSTOMER, request());
        var expected = jdbc.queryForList("select id from outbox_events order by created_at, id", UUID.class);
        var poller = new com.insurance.platform.quote.repository.OutboxPollingRepository(dataSource);
        try (var first = poller.claim(2).orElseThrow()) {
            assertThat(first.events()).extracting(com.insurance.platform.quote.repository.OutboxPollingRepository.PendingEvent::id)
                    .containsExactlyElementsOf(expected.subList(0, 2));
            // A second connection/instance must not claim rows while the first owns the session lock.
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(() -> poller.claim(2)).get(5, java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
            }
            assertThat(jdbc.queryForObject("""
                    select count(*) from pg_stat_activity a join pg_locks l on l.pid=a.pid
                    where l.locktype='advisory' and l.classid=741016 and l.objid=1
                    and a.xact_start is not null
                    """, Long.class)).isZero();
            var event = first.events().getFirst();
            first.failed(event.id());
            first.published(event.id(), NOW.plusSeconds(10));
            var row = jdbc.queryForMap("select * from outbox_events where id=?", event.id());
            assertThat(row.get("status")).isEqualTo("PUBLISHED");
            assertThat(row.get("retry_count")).isEqualTo(1);
            assertThat(((java.sql.Timestamp) row.get("published_at")).toInstant()).isEqualTo(NOW.plusSeconds(10));
            assertThat(row.get("payload").toString()).isEqualTo(event.payload());
            assertThat(row.get("event_id")).isEqualTo(event.eventId());
            assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(NOW);
        }
        try (var next = poller.claim(50).orElseThrow()) {
            assertThat(next.events()).extracting(com.insurance.platform.quote.repository.OutboxPollingRepository.PendingEvent::id)
                    .containsExactlyElementsOf(expected.subList(1, 3));
        }
    }

    @Test void acknowledgedSendWithFailedStatusUpdateIsPublishedAgainWithIdenticalPayload() {
        service.create(CUSTOMER, request());
        var poller = new com.insurance.platform.quote.repository.OutboxPollingRepository(dataSource);
        var publisher = new com.insurance.platform.quote.service.QuoteOutboxPublisher(poller, kafka, clock, "insurance.quote.events", 50);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT phase16_pending CHECK (status='PENDING') NOT VALID");
        try { publisher.poll(); } finally { jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT phase16_pending"); }
        assertThat(jdbc.queryForObject("select status from outbox_events", String.class)).isEqualTo("PENDING");
        publisher.poll();
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka, times(2)).send(eq("insurance.quote.events"), anyString(), payload.capture());
        assertThat(payload.getAllValues().getFirst()).isEqualTo(payload.getAllValues().getLast());
        assertThat(jdbc.queryForObject("select status from outbox_events", String.class)).isEqualTo("PUBLISHED");
        publisher.poll(); verifyNoMoreInteractions(kafka);
    }

    private com.insurance.platform.quote.dto.QuoteResponse persistInTransaction(UUID owner) {
        return persistence.create(owner, request(), new VehicleSnapshot(BRAND, "Toyota", MODEL, "Camry", 2021,
                request().vehicleValue(), 1500), price());
    }
}
