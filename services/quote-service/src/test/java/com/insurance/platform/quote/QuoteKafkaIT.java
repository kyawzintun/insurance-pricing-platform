package com.insurance.platform.quote;

import com.insurance.platform.quote.client.PricingClient;
import com.insurance.platform.quote.repository.OutboxPollingRepository;
import com.insurance.platform.quote.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static com.insurance.platform.quote.QuoteFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Disposable PostgreSQL + Kafka. Manual inspection never changes a consumer group offset. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@Testcontainers
class QuoteKafkaIT {
    private static final String TOPIC = "insurance.quote.events";
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17.11-bookworm");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("auth.jwt.secret", QuoteJwtSupport::secret);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("outbox.publisher.enabled", () -> "true");
        registry.add("outbox.publisher.poll-interval", () -> "200");
    }
    @BeforeAll static void topic() throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
    }
    @Autowired org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor scheduling;
    @Autowired QuoteService service;
    @Autowired QuoteRetrievalService retrieval;
    @Autowired VehicleReferenceService catalog;
    @Autowired OutboxPollingRepository poller;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PricingClient pricing;
    @MockitoBean Clock clock;
    @MockitoSpyBean QuoteOutboxPublisher scheduledPublisher;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @Test void scheduledOutboxPublicationOutageRecoveryAndDuplicateWindow() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        doReturn(price()).when(pricing).calculate(any());
        var automatic = new AtomicBoolean(false);
        doAnswer(call -> automatic.get() ? call.callRealMethod() : null).when(scheduledPublisher).poll();
        var manual = new QuoteOutboxPublisher(poller, kafka, clock, TOPIC, 50);
        var partitions = List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1), new TopicPartition(TOPIC, 2));
        var config = new HashMap<String,Object>();
        config.put("bootstrap.servers", KAFKA.getBootstrapServers());
        config.put("key.deserializer", StringDeserializer.class);
        config.put("value.deserializer", StringDeserializer.class);
        config.put("enable.auto.commit", false);
        config.put("allow.auto.create.topics", false);
        try (var consumer = new KafkaConsumer<String,String>(config)) {
            consumer.assign(partitions); consumer.seekToBeginning(partitions);
            var before = consumer.endOffsets(partitions);
            var quote = new TransactionTemplate(transactions).execute(status -> {
                var created = service.create(CUSTOMER, request());
                assertThat(jdbc.queryForObject("select status from outbox_events", String.class)).isEqualTo("PENDING");
                // Even a separately running publisher cannot see this uncommitted event.
                manual.poll();
                assertThat(consumer.endOffsets(partitions)).isEqualTo(before);
                return created;
            });
            automatic.set(true); // Actual @Scheduled invocation performs publication.
            await(() -> status(quote.id()).equals("PUBLISHED"));
            automatic.set(false);
            var records = read(consumer, 1);
            assertThat(records).hasSize(1);
            var record = records.getFirst();
            assertThat(record.topic()).isEqualTo(TOPIC); assertThat(record.key()).isEqualTo(quote.id().toString());
            assertThat(record.headers().toArray()).isEmpty();
            assertThat(record.value()).isEqualTo(payload(quote.id()));
            var json = JsonMapper.builder().build().readTree(record.value());
            assertThat(json.size()).isEqualTo(9);
            assertThat(json.get("eventType").asText()).isEqualTo("QuotePriced");
            assertThat(json.get("eventVersion").asInt()).isEqualTo(1);
            assertThat(json.get("aggregateType").asText()).isEqualTo("Quote");
            assertThat(json.get("aggregateId").asText()).isEqualTo(quote.id().toString());
            assertThat(json.get("producer").asText()).isEqualTo("quote-service");
            assertThat(json.get("occurredAt").asText()).isEqualTo(NOW.toString());
            assertThat(json.get("eventId").asText()).isEqualTo(jdbc.queryForObject("select event_id::text from outbox_events", String.class));
            assertThat(UUID.fromString(json.get("correlationId").asText())).isNotNull();
            var data = json.get("data");
            assertThat(data.size()).isEqualTo(9);
            assertThat(data.get("quoteId").asText()).isEqualTo(quote.id().toString());
            assertThat(data.get("quoteReference").asText()).isEqualTo(quote.quoteReference());
            assertThat(data.get("customerId").asText()).isEqualTo(CUSTOMER.toString());
            assertThat(data.get("status").asText()).isEqualTo("PRICED");
            assertThat(data.get("coverageType").asText()).isEqualTo("COMPREHENSIVE");
            assertThat(data.get("finalPremium").decimalValue()).isEqualByComparingTo(quote.pricing().finalPremium());
            assertThat(data.get("currency").asText()).isEqualTo(quote.pricing().currency());
            assertThat(data.get("pricedAt").asText()).isEqualTo(quote.pricedAt().toString());
            assertThat(data.get("expiresAt").asText()).isEqualTo(quote.expiresAt().toString());
            assertThat(total(consumer.endOffsets(partitions)) - total(before)).isEqualTo(1);
            retrieval.get(quote.id(), CUSTOMER, false); retrieval.list(CUSTOMER, false, 0, 20);
            catalog.brands(); catalog.models(BRAND); manual.poll();
            assertThat(jdbc.queryForObject("select count(*) from outbox_events", Long.class)).isEqualTo(1);
            assertThat(consumer.poll(Duration.ofMillis(500)).isEmpty()).isTrue();

            // A real broker outage affects only background publishing; HTTP still commits a durable intent.
            KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
            UUID outageQuote;
            String stored;
            try {
                try (var client = java.net.http.HttpClient.newHttpClient()) {
                    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/v1/quotes"))
                            .header("Authorization", "Bearer " + QuoteJwtSupport.token("CUSTOMER"))
                            .header("Content-Type", "application/json").header("X-Correlation-ID", "outage-correlation")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(INPUT)).build();
                    var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(201);
                    outageQuote = UUID.fromString(JsonMapper.builder().build().readTree(response.body()).get("id").asText());
                }
                stored = payload(outageQuote);
                manual.poll();
                assertThat(status(outageQuote)).isEqualTo("PENDING");
                assertThat(jdbc.queryForObject("select retry_count from outbox_events where aggregate_id=?", Integer.class, outageQuote.toString())).isEqualTo(1);
            } finally { KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
            automatic.set(true);
            await(() -> status(outageQuote).equals("PUBLISHED"));
            automatic.set(false);
            int recoveredCount = (int) (total(consumer.endOffsets(partitions)) - total(before) - 1);
            assertThat(recoveredCount).isGreaterThanOrEqualTo(1);
            var recovered = read(consumer, recoveredCount);
            assertThat(recovered).hasSize(recoveredCount).allSatisfy(r -> {
                assertThat(r.value()).isEqualTo(stored);
                assertThat(r.key()).isEqualTo(outageQuote.toString());
            });
            assertThat(JsonMapper.builder().build().readTree(stored).get("correlationId").asText()).isEqualTo("outage-correlation");

            // Kafka acknowledges, but a disposable database constraint prevents marking PUBLISHED.
            var duplicateQuote = service.create(CUSTOMER, request());
            jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT phase16_pending CHECK (status='PENDING') NOT VALID");
            try { manual.poll(); } finally { jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT phase16_pending"); }
            assertThat(status(duplicateQuote.id())).isEqualTo("PENDING");
            manual.poll();
            var duplicate = read(consumer, 2);
            assertThat(duplicate).hasSize(2);
            assertThat(duplicate.getFirst().value()).isEqualTo(duplicate.getLast().value()).isEqualTo(payload(duplicateQuote.id()));
            assertThat(duplicate.getFirst().key()).isEqualTo(duplicateQuote.id().toString()).isEqualTo(duplicate.getLast().key());
            assertThat(duplicate.getFirst().partition()).isEqualTo(duplicate.getLast().partition());
            assertThat(status(duplicateQuote.id())).isEqualTo("PUBLISHED");
            manual.poll();
            assertThat(consumer.poll(Duration.ofSeconds(1)).isEmpty()).isTrue();
        } finally { automatic.set(false); scheduling.destroy(); }
    }
    private String status(UUID id) { return jdbc.queryForObject("select status from outbox_events where aggregate_id=?", String.class, id.toString()); }
    private String payload(UUID id) { return jdbc.queryForObject("select payload::text from outbox_events where aggregate_id=?", String.class, id.toString()); }
    private long total(Map<TopicPartition, Long> offsets) { return offsets.values().stream().mapToLong(Long::longValue).sum(); }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    private List<ConsumerRecord<String,String>> read(KafkaConsumer<String,String> consumer, int expected) {
        var records = new ArrayList<ConsumerRecord<String,String>>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (records.size() < expected && System.nanoTime() < deadline) consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        return records;
    }
}
