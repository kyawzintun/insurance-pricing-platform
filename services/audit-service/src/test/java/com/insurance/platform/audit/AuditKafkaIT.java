package com.insurance.platform.audit;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.insurance.platform.audit.event.QuoteAuditListener;
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
    @MockitoSpyBean QuoteAuditListener listener;
    @Test void committedDatabaseSurvivesCrashAndSameOffsetRedeliveryIsSuccessful() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        var partition = new TopicPartition(TOPIC, 0);
        var container = registry.getListenerContainer("quote-audit");
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()));
             var producer = new KafkaProducer<String,String>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(20, TimeUnit.SECONDS);
            // Produce before starting the group: proves earliest historical consumption.
            producer.send(new ProducerRecord<>(TOPIC, 0, QUOTE.toString(), json())).get(20, TimeUnit.SECONDS);
            var crashOnce = new AtomicBoolean(true);
            doAnswer(invocation -> {
                invocation.callRealMethod(); // Service transaction has already committed here.
                if (crashOnce.getAndSet(false)) throw new IllegalStateException("test crash after DB commit before offset commit");
                return null;
            }).when(listener).receive(any());
            container.start();
            awaitStopped(admin, container);
            assertCounts(1, 1);
            var beforeRestart = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get().get(partition);
            assertThat(beforeRestart == null || beforeRestart.offset() == 0).isTrue();
            var originalMarker = jdbc.queryForMap("SELECT * FROM processed_events");
            assertThat(originalMarker.get("consumer_name")).isEqualTo("audit-service"); // Not the test group ID.
            when(clock.instant()).thenReturn(NOW.plusSeconds(60));
            container.start(); // Same group, same record/offset; no offset reset and no second publication.
            awaitOffset(admin, partition, 1);
            var captures = org.mockito.ArgumentCaptor.forClass(org.apache.kafka.clients.consumer.ConsumerRecord.class);
            verify(listener, times(2)).receive(captures.capture());
            assertThat(captures.getAllValues()).allSatisfy(record -> {
                assertThat(record.offset()).isZero();
                assertThat(record.partition()).isZero();
                assertThat(record.value()).isEqualTo(json());
            });
            assertCounts(1, 1);
            assertThat(jdbc.queryForMap("SELECT * FROM processed_events")).isEqualTo(originalMarker);
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

            // A separate second record with the same event ID is also safe (distinct from redelivery above).
            producer.send(new ProducerRecord<>(TOPIC, 0, QUOTE.toString(), json())).get(20, TimeUnit.SECONDS);
            awaitOffset(admin, partition, 2);
            assertCounts(1, 1);
            assertThat(jdbc.queryForMap("SELECT * FROM processed_events")).isEqualTo(originalMarker);
            assertThat(jdbc.queryForMap("SELECT * FROM audit_records")).isEqualTo(row);
            producer.send(new ProducerRecord<>(TOPIC, 0, "other", "{\"eventType\":\"FutureEvent\"}")).get(20, TimeUnit.SECONDS);
            awaitOffset(admin, partition, 3);
            assertCounts(1, 1);
            producer.send(new ProducerRecord<>(TOPIC, 0, "invalid", "{broken-json")).get(20, TimeUnit.SECONDS);
            awaitStopped(admin, container);
            assertThat(admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get().get(partition).offset()).isEqualTo(3);
            assertCounts(1, 1);
            assertThat(admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get().get(partition).offset()).isEqualTo(4);
        } finally { container.stop(); }
    }
    private void assertCounts(long business, long markers) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_records", Long.class)).isEqualTo(business);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isEqualTo(markers);
    }
    private void awaitOffset(Admin admin, TopicPartition partition, long offset) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(partition);
            assertThat(committed).isNotNull();
            assertThat(committed.offset()).isEqualTo(offset);
        });
    }
    private void awaitStopped(Admin admin, org.springframework.kafka.listener.MessageListenerContainer container) {
        await().atMost(Duration.ofSeconds(30)).until(() -> !container.isRunning());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(admin.describeConsumerGroups(List.of(GROUP))
                .all().get(5, TimeUnit.SECONDS).get(GROUP).members()).isEmpty());
    }
}
