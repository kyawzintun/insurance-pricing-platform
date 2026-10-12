package com.insurance.platform.quote;

import com.insurance.platform.quote.client.PricingClient;
import com.insurance.platform.quote.dto.VehicleSnapshot;
import com.insurance.platform.quote.event.*;
import com.insurance.platform.quote.exception.QuoteException;
import com.insurance.platform.quote.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.context.ApplicationEventPublisher;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static com.insurance.platform.quote.QuoteFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in disposable broker + database. The inspecting client uses manual assignment, no consumer group. */
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
    }
    @BeforeAll static void topic() throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
    }
    @Autowired QuoteService service;
    @Autowired QuotePersistenceService persistence;
    @Autowired QuoteRetrievalService retrieval;
    @Autowired VehicleReferenceService catalog;
    @Autowired QuotePricedEventFactory factory;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PricingClient pricing;
    @MockitoBean Clock clock;

    @Test void committedCreationHasOneJsonRecordAndSameQuoteKeyStaysOnSamePartition() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        doReturn(price()).when(pricing).calculate(any());
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
            var quote = service.create(CUSTOMER, request());
            var records = read(consumer, 1);
            assertThat(records).hasSize(1);
            var record = records.getFirst();
            assertThat(record.topic()).isEqualTo(TOPIC); assertThat(record.key()).isEqualTo(quote.id().toString());
            assertThat(record.headers().toArray()).isEmpty();
            var json = JsonMapper.builder().build().readTree(record.value());
            assertThat(json.get("eventType").asText()).isEqualTo("QuotePriced");
            assertThat(json.get("eventVersion").asInt()).isEqualTo(1);
            assertThat(json.get("aggregateType").asText()).isEqualTo("Quote");
            assertThat(json.get("aggregateId").asText()).isEqualTo(quote.id().toString());
            assertThat(json.get("producer").asText()).isEqualTo("quote-service");
            assertThat(json.get("occurredAt").asText()).isEqualTo(NOW.toString());
            assertThat(UUID.fromString(json.get("eventId").asText())).isNotNull();
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
            var afterCreate = consumer.endOffsets(partitions);
            assertThat(afterCreate.values().stream().mapToLong(Long::longValue).sum()
                    - before.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1);

            // Retrieval and catalog reads, failed Pricing, flush failure, and outer rollback must add no records.
            retrieval.get(quote.id(), CUSTOMER, false); retrieval.list(CUSTOMER, false, 0, 20);
            catalog.brands(); catalog.models(BRAND);
            when(pricing.calculate(any())).thenThrow(QuoteException.pricingUnavailable());
            assertThatThrownBy(() -> service.create(CUSTOMER, request())).isInstanceOf(QuoteException.class);
            doReturn(price()).when(pricing).calculate(any());
            var invalid = new VehicleSnapshot(BRAND, "x".repeat(101), MODEL, "Model", 2021, new BigDecimal("700000"), 1500);
            assertThatThrownBy(() -> persistence.create(CUSTOMER, request(), invalid, price())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                service.create(CUSTOMER, request()); status.setRollbackOnly();
            });
            assertThat(consumer.poll(Duration.ofSeconds(2)).isEmpty()).isTrue();
            assertThat(consumer.endOffsets(partitions)).isEqualTo(afterCreate);
            assertThat(jdbc.queryForObject("select count(*) from quotes", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from outbox_events", Long.class)).isZero();

            // Controlled extra publications only in this disposable test, not an application retry mechanism.
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                events.publishEvent(factory.create(CUSTOMER, quote)); events.publishEvent(factory.create(CUSTOMER, quote));
            });
            var repeated = read(consumer, 2);
            assertThat(repeated).hasSize(2).allSatisfy(r -> {
                assertThat(r.key()).isEqualTo(record.key()); assertThat(r.partition()).isEqualTo(record.partition());
            });
            var ids = new HashSet<String>(); ids.add(json.get("eventId").asText());
            for (var r : repeated) ids.add(JsonMapper.builder().build().readTree(r.value()).get("eventId").asText());
            assertThat(ids).hasSize(3);
        }
    }
    private List<ConsumerRecord<String,String>> read(KafkaConsumer<String,String> consumer, int expected) {
        var records = new ArrayList<ConsumerRecord<String,String>>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (records.size() < expected && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        }
        return records;
    }
}
