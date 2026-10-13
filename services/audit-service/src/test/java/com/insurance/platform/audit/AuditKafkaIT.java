package com.insurance.platform.audit;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.insurance.platform.audit.AuditFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@ActiveProfiles("integration")
@Testcontainers
class AuditKafkaIT {
    static final String TOPIC = "insurance.quote.events";
    static final String GROUP = "audit-service-test";
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17.11-bookworm");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DATABASE::getJdbcUrl);
        r.add("spring.datasource.username", DATABASE::getUsername);
        r.add("spring.datasource.password", DATABASE::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.kafka.consumer.group-id", () -> GROUP);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaListenerEndpointRegistry registry;
    @MockitoBean Clock clock;
    @Test void consumesHistoryCommitsAfterDatabaseAndStopsWithoutSkippingInvalidRecord() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        var partition = new TopicPartition(TOPIC, 0);
        var container = registry.getListenerContainer("quote-audit");
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()));
             var producer = new KafkaProducer<String,String>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(20, TimeUnit.SECONDS);
            // Produce before starting the group: proves earliest historical consumption.
            producer.send(new ProducerRecord<>(TOPIC, 0, QUOTE.toString(), json())).get(20, TimeUnit.SECONDS);
            container.start();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_records", Long.class)).isEqualTo(1);
                var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertThat(offsets.get(partition)).isNotNull();
                assertThat(offsets.get(partition).offset()).isEqualTo(1);
            });
            var row = jdbc.queryForMap("SELECT * FROM audit_records");
            assertThat(row.get("event_id")).isEqualTo(EVENT);
            assertThat(row.get("event_type")).isEqualTo("QuotePriced");
            assertThat(row.get("actor_type")).isEqualTo("CUSTOMER");
            assertThat(row.get("actor_id")).isEqualTo(CUSTOMER.toString());
            assertThat(row.get("entity_type")).isEqualTo("Quote");
            assertThat(row.get("entity_id")).isEqualTo(QUOTE.toString());
            assertThat(row.get("correlation_id")).isEqualTo("phase14-test");
            assertThat(row.get("producer")).isEqualTo("quote-service");
            assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(java.time.Instant.parse("2026-10-12T09:00:00Z"));
            assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(NOW);
            AuditEventTests.assertMetadata(row.get("metadata").toString());

            // A valid unknown event is intentionally skipped and its offset advances.
            producer.send(new ProducerRecord<>(TOPIC, 0, "other", "{\"eventType\":\"FutureEvent\"}")).get(20, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata().get().get(partition).offset()).isEqualTo(2));
            // Existing UNIQUE(event_id) rejects redelivery. It must stop without committing offset 2.
            producer.send(new ProducerRecord<>(TOPIC, 0, QUOTE.toString(), json())).get(20, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(15)).until(() -> !container.isRunning());
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(admin.describeConsumerGroups(List.of(GROUP))
                    .all().get().get(GROUP).members()).isEmpty());
            assertThat(admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get().get(partition).offset()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_records", Long.class)).isEqualTo(1);
            // Disposable test DB only: remove the conflicting row to demonstrate replay after an explicit restart.
            jdbc.update("DELETE FROM audit_records WHERE event_id = ?", EVENT);
            container.start();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_records", Long.class)).isEqualTo(1);
                assertThat(admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get().get(partition).offset()).isEqualTo(3);
            });
            producer.send(new ProducerRecord<>(TOPIC, 0, "invalid", "{broken-json")).get(20, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(15)).until(() -> !container.isRunning());
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(admin.describeConsumerGroups(List.of(GROUP))
                    .all().get().get(GROUP).members()).isEmpty());
            assertThat(admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get().get(partition).offset()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_records", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isZero();
            assertThat(admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get().get(partition).offset()).isEqualTo(4);
        } finally { container.stop(); }
    }
}
