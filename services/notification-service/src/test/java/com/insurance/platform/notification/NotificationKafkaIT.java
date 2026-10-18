package com.insurance.platform.notification;

import com.insurance.platform.notification.event.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.insurance.platform.notification.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "kafka.consumer.backoff-ms=100"})
@ActiveProfiles("integration")
@Testcontainers
class NotificationKafkaIT {
    static final String USER_TOPIC = "custom.user.events";
    static final String USER_DLQ = "custom.user.failures";
    static final String TOPIC = "insurance.quote.events";
    static final String DLQ = "insurance.quote.events.dlq";
    static final String GROUP = "notification-service-test";
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17.11-bookworm");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DATABASE::getJdbcUrl);
        r.add("spring.datasource.username", DATABASE::getUsername);
        r.add("spring.datasource.password", DATABASE::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("user.events.topic", () -> USER_TOPIC);
        r.add("user.events.dlq-topic", () -> USER_DLQ);
        r.add("spring.kafka.consumer.group-id", () -> GROUP);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired DefaultErrorHandler errorHandler;
    @MockitoBean Clock clock;
    @MockitoSpyBean QuoteEventListener listener;
    @MockitoSpyBean QuoteDeadLetterRecoverer recoverer;

    @Test void idempotencyRetriesDlqAndFailedRecoveryPreserveDatabaseAndOffsets() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        var partition = new TopicPartition(TOPIC, 0);
        var dlqPartition = new TopicPartition(DLQ, 0);
        var container = (AbstractMessageListenerContainer<?, ?>) registry.getListenerContainer("quote-notifications");
        var attempts = new ConcurrentHashMap<Long, Integer>();
        var crashOnce = new AtomicBoolean(true);
        var transientOnce = new AtomicBoolean(true);
        var failedRecoveries = new AtomicInteger();
        doAnswer(call -> {
            try { return call.callRealMethod(); }
            catch (RuntimeException failure) { failedRecoveries.incrementAndGet(); throw failure; }
        }).when(recoverer).accept(any(), any());
        doAnswer(call -> {
            ConsumerRecord<byte[], byte[]> record = call.getArgument(0);
            attempts.merge(record.offset(), 1, Integer::sum);
            try {
                call.callRealMethod();
            } catch (RuntimeException failure) {
                if (record.offset() == 3 && transientOnce.getAndSet(false)) {
                    assertCounts(1, 1); // First failed DB transaction left no partial row or marker.
                    jdbc.execute("ALTER TABLE notifications DROP CONSTRAINT phase17_failure");
                }
                throw failure;
            }
            if (record.offset() == 0 && crashOnce.getAndSet(false)) {
                throw new IllegalStateException("test process interruption after DB commit before offset commit");
            }
            return null;
        }).when(listener).receive(any());
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()));
             var producer = new KafkaProducer<byte[], byte[]>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                     "key.serializer", ByteArraySerializer.class, "value.serializer", ByteArraySerializer.class));
             var dlqReader = new KafkaConsumer<byte[], byte[]>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                     "key.deserializer", ByteArrayDeserializer.class, "value.deserializer", ByteArrayDeserializer.class,
                     "enable.auto.commit", false, "allow.auto.create.topics", false))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1), new NewTopic(DLQ, 3, (short) 1)))
                    .all().get(20, TimeUnit.SECONDS);
            dlqReader.assign(List.of(dlqPartition)); dlqReader.seekToBeginning(List.of(dlqPartition));
            // Preserve Phase 15 true crash/restart test. Only this injection uses a stopping handler to mimic process loss.
            container.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
            send(producer, bytes(QUOTE.toString()), bytes(json()));
            container.start();
            await().atMost(Duration.ofSeconds(30)).until(() -> !container.isRunning());
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(admin.describeConsumerGroups(List.of(GROUP))
                    .all().get(5, TimeUnit.SECONDS).get(GROUP).members()).isEmpty());
            assertCounts(1, 1);
            assertThat(offset(admin, partition)).isZero();
            var originalMarker = jdbc.queryForMap("SELECT * FROM processed_events");
            when(clock.instant()).thenReturn(NOW.plusSeconds(60));
            container.setCommonErrorHandler(errorHandler); // Restore actual Phase 17 policy for the remainder.
            container.start();
            awaitOffset(admin, partition, 1);
            assertThat(attempts.get(0L)).isEqualTo(2); // Same source record, no republish or offset reset.
            assertThat(jdbc.queryForMap("SELECT * FROM processed_events")).isEqualTo(originalMarker);
            var row = jdbc.queryForMap("SELECT * FROM notifications");
            assertThat(row.get("event_id")).isEqualTo(EVENT);
            assertThat(row.get("quote_id")).isEqualTo(QUOTE);
            assertThat(row.get("customer_id")).isEqualTo(CUSTOMER);
            assertThat(row.get("type")).isEqualTo("QUOTE_PRICED");
            assertThat(row.get("status")).isEqualTo("CREATED");
            assertThat(row.get("message")).isEqualTo("Your quote Q-TEST-123 is ready. Premium: 11200.00 THB.");
            assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(NOW);
            assertThat(((java.sql.Timestamp) row.get("processed_at")).toInstant()).isEqualTo(NOW);

            send(producer, bytes(QUOTE.toString()), bytes(json()));
            awaitOffset(admin, partition, 2);
            assertThat(attempts.get(1L)).isEqualTo(1); // Duplicate is successful, never retried.
            assertCounts(1, 1);
            assertThat(jdbc.queryForMap("SELECT * FROM notifications")).isEqualTo(row);
            assertThat(jdbc.queryForMap("SELECT * FROM processed_events")).isEqualTo(originalMarker);
            send(producer, bytes("unknown"), bytes("{\"eventType\":\"FutureEvent\"}"));
            awaitOffset(admin, partition, 3);
            assertThat(end(admin, dlqPartition)).isZero();

            // Real transient PostgreSQL failure, recovered on the second processing attempt.
            jdbc.execute("ALTER TABLE notifications ADD CONSTRAINT phase17_failure CHECK (false) NOT VALID");
            var transientEvent = UUID.randomUUID();
            send(producer, bytes(QUOTE.toString()), bytes(json().replace(EVENT.toString(), transientEvent.toString())));
            awaitOffset(admin, partition, 4);
            assertThat(attempts.get(3L)).isEqualTo(2);
            assertCounts(2, 2);
            assertThat(end(admin, dlqPartition)).isZero();

            // Three real failing transactions then one acknowledged DLQ record, with no successful marker.
            jdbc.execute("ALTER TABLE notifications ADD CONSTRAINT phase17_failure CHECK (false) NOT VALID");
            byte[] exhausted = bytes(json().replace(EVENT.toString(), UUID.randomUUID().toString()));
            send(producer, bytes(QUOTE.toString()), exhausted);
            awaitOffset(admin, partition, 5);
            assertThat(attempts.get(4L)).isEqualTo(3);
            assertCounts(2, 2);
            assertDlq(read(dlqReader), bytes(QUOTE.toString()), exhausted, 4, 3, "PROCESSING_FAILURE");
            jdbc.execute("ALTER TABLE notifications DROP CONSTRAINT phase17_failure");

            byte[] badUtf8 = {(byte) 0xff, '{', (byte) 0xc3};
            byte[] binaryKey = {(byte) 0xfe, 'k'};
            send(producer, binaryKey, badUtf8); awaitOffset(admin, partition, 6);
            assertThat(attempts.get(5L)).isEqualTo(1);
            assertDlq(read(dlqReader), binaryKey, badUtf8, 5, 1, "MALFORMED_UTF8");
            byte[] version = bytes(json().replace("\"eventVersion\":1", "\"eventVersion\":2"));
            send(producer, bytes("version"), version); awaitOffset(admin, partition, 7);
            assertThat(attempts.get(6L)).isEqualTo(1);
            assertDlq(read(dlqReader), bytes("version"), version, 6, 1, "UNSUPPORTED_EVENT_VERSION");
            byte[] missing = bytes(json().replace("\"eventId\":\"" + EVENT + "\",", ""));
            send(producer, bytes("missing"), missing); awaitOffset(admin, partition, 8);
            assertThat(attempts.get(7L)).isEqualTo(1);
            assertDlq(read(dlqReader), bytes("missing"), missing, 7, 1, "INVALID_ENVELOPE");
            byte[] malformed = bytes("{broken-json");
            send(producer, bytes("malformed"), malformed); awaitOffset(admin, partition, 9);
            assertThat(attempts.get(8L)).isEqualTo(1);
            assertDlq(read(dlqReader), bytes("malformed"), malformed, 8, 1, "MALFORMED_JSON");
            assertCounts(2, 2);
            assertThat(end(admin, dlqPartition)).isEqualTo(5);
            // Later valid work still runs after exhausted/invalid records.
            send(producer, bytes(QUOTE.toString()), bytes(json().replace(EVENT.toString(), UUID.randomUUID().toString())));
            awaitOffset(admin, partition, 10); assertCounts(3, 3);

            // Remove only the disposable DLQ to exercise an actual Kafka publication failure.
            admin.deleteTopics(List.of(DLQ)).all().get(20, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(15)).until(() -> !admin.listTopics().names().get().contains(DLQ));
            send(producer, bytes("dlq-outage"), malformed);
            await().atMost(Duration.ofSeconds(30)).until(() -> failedRecoveries.get() > 0);
            assertThat(offset(admin, partition)).isEqualTo(10); // Original failed record is not committed.
            assertCounts(3, 3);
            admin.createTopics(List.of(new NewTopic(DLQ, 3, (short) 1))).all().get(20, TimeUnit.SECONDS);
            awaitOffset(admin, partition, 11);
            try (var restoredReader = new KafkaConsumer<byte[], byte[]>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                    "key.deserializer", ByteArrayDeserializer.class, "value.deserializer", ByteArrayDeserializer.class,
                    "enable.auto.commit", false))) {
                restoredReader.assign(List.of(dlqPartition)); restoredReader.seekToBeginning(List.of(dlqPartition));
                assertDlq(read(restoredReader), bytes("dlq-outage"), malformed, 10, 1, "MALFORMED_JSON");
            }
            assertThat(end(admin, dlqPartition)).isEqualTo(1);
            assertCounts(3, 3);
            assertThat(end(admin, partition)).isEqualTo(11);
            var expiration = expiredJson().replace(EVENT.toString(), java.util.UUID.randomUUID().toString());
            send(producer, bytes(QUOTE.toString()), bytes(expiration));
            send(producer, bytes(QUOTE.toString()), bytes(expiration));
            awaitOffset(admin, partition, 13);
            assertCounts(4, 4);
            assertThat(jdbc.queryForObject("select count(*) from notifications where type=?", Long.class,
                    "QUOTE_EXPIRED")).isEqualTo(1);
            send(producer, bytes("invalid-expired"), bytes(expiration.replace("\"eventVersion\":1", "\"eventVersion\":2")));
            awaitOffset(admin, partition, 14);
            assertCounts(4, 4);
            assertThat(end(admin, dlqPartition)).isEqualTo(2);

        } finally {
            container.stop();
            jdbc.execute("ALTER TABLE notifications DROP CONSTRAINT IF EXISTS phase17_failure");
        }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private void send(KafkaProducer<byte[], byte[]> producer, byte[] key, byte[] value) throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, 0, key, value)).get(20, TimeUnit.SECONDS);
    }
    private void assertCounts(long business, long markers) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(business);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isEqualTo(markers);
    }
    private long offset(Admin admin, TopicPartition partition) throws Exception {
        var position = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(partition);
        return position == null ? 0 : position.offset();
    }
    private long end(Admin admin, TopicPartition partition) throws Exception {
        return admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get(5, TimeUnit.SECONDS).get(partition).offset();
    }
    private void awaitOffset(Admin admin, TopicPartition partition, long expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(offset(admin, partition)).isEqualTo(expected));
    }
    private ConsumerRecord<byte[], byte[]> read(KafkaConsumer<byte[], byte[]> reader) {
        var records = new ArrayList<ConsumerRecord<byte[], byte[]>>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            reader.poll(Duration.ofMillis(100)).forEach(records::add); return !records.isEmpty();
        });
        assertThat(records).hasSize(1); return records.getFirst();
    }
    private void assertDlq(ConsumerRecord<byte[], byte[]> record, byte[] key, byte[] value,
                           long sourceOffset, int attempt, String reason) {
        assertThat(record.topic()).isEqualTo(DLQ); assertThat(record.partition()).isZero();
        assertThat(record.key()).containsExactly(key); assertThat(record.value()).containsExactly(value);
        assertThat(text(record, "x-original-topic")).isEqualTo(TOPIC);
        assertThat(text(record, "x-original-partition")).isEqualTo("0");
        assertThat(text(record, "x-original-offset")).isEqualTo(Long.toString(sourceOffset));
        assertThat(record.headers().lastHeader("x-original-key").value()).containsExactly(key);
        assertThat(text(record, "x-consumer-service")).isEqualTo("notification-service");
        assertThat(text(record, "x-consumer-group")).isEqualTo(GROUP);
        assertThat(text(record, "x-attempt-count")).isEqualTo(Integer.toString(attempt));
        assertThat(text(record, "x-error-message")).isEqualTo(reason);
        assertThat(text(record, "x-exception-class")).isNotBlank();
        assertThat(Instant.parse(text(record, "x-failed-at"))).isEqualTo(NOW.plusSeconds(60));
        for (var header : record.headers()) assertThat(header.key()).doesNotContain("stacktrace");
    }
    private String text(ConsumerRecord<?, ?> record, String header) {
        return new String(record.headers().lastHeader(header).value(), StandardCharsets.UTF_8);
    }

    @Test void userRegisteredIsIdempotentAndInvalidVersionUsesUserDlq() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        String userTopic = USER_TOPIC;
        var tp = new TopicPartition(userTopic, 0);
        var userId = UUID.randomUUID(); var eventId = UUID.randomUUID();
        var json = """
                {"eventId":"%s","eventType":"UserRegistered","eventVersion":1,"aggregateType":"User",
                 "aggregateId":"%s","occurredAt":"%s","correlationId":"phase19-test","producer":"auth-service",
                 "data":{"userId":"%s","email":"temporary@example.com","role":"CUSTOMER","status":"ACTIVE","registeredAt":"%s"}}
                """.formatted(eventId,userId,NOW,userId,NOW);
        try(var admin = Admin.create(Map.of("bootstrap.servers",KAFKA.getBootstrapServers()));
            var producer = new KafkaProducer<byte[],byte[]>(Map.of("bootstrap.servers",KAFKA.getBootstrapServers(),
                "key.serializer",ByteArraySerializer.class,"value.serializer",ByteArraySerializer.class))) {
            admin.createTopics(List.of(new NewTopic(userTopic,3,(short)1),new NewTopic(USER_DLQ,3,(short)1))).all().get(20,TimeUnit.SECONDS);
            var container = registry.getListenerContainer("user-notification");
            try {
                container.start();
                producer.send(new ProducerRecord<>(userTopic,0,bytes(userId.toString()),bytes(json))).get(10,TimeUnit.SECONDS);
                producer.send(new ProducerRecord<>(userTopic,0,bytes(userId.toString()),bytes(json))).get(10,TimeUnit.SECONDS);
                awaitOffset(admin,tp,2);
                assertThat(jdbc.queryForObject("select count(*) from notifications where event_id=?",Integer.class,eventId)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id=?",Integer.class,eventId)).isEqualTo(1);
                var row=jdbc.queryForMap("select * from notifications where event_id=?",eventId);
                assertThat(row.get("type")).isEqualTo("USER_REGISTERED");assertThat(row.get("quote_id")).isNull();
                producer.send(new ProducerRecord<>(userTopic,0,bytes(userId.toString()),bytes(json.replace("\"eventVersion\":1","\"eventVersion\":2")))).get(10,TimeUnit.SECONDS);
                producer.send(new ProducerRecord<>(userTopic,0,bytes(userId.toString()),bytes("{broken"))).get(10,TimeUnit.SECONDS);
                awaitOffset(admin,tp,4);
                try (var reader = new KafkaConsumer<byte[],byte[]>(Map.of("bootstrap.servers",KAFKA.getBootstrapServers(),
                        "key.deserializer",ByteArrayDeserializer.class,"value.deserializer",ByteArrayDeserializer.class,"enable.auto.commit",false))) {
                    var destination = new TopicPartition(USER_DLQ,0);
                    reader.assign(List.of(destination)); reader.seekToBeginning(List.of(destination));
                    var dead = new ArrayList<ConsumerRecord<byte[],byte[]>>();
                    await().atMost(Duration.ofSeconds(15)).until(() -> { reader.poll(Duration.ofMillis(100)).forEach(dead::add); return dead.size()==2; });
                    assertThat(dead.get(0).key()).containsExactly(bytes(userId.toString()));
                    assertThat(dead.get(0).value()).containsExactly(bytes(json.replace("\"eventVersion\":1","\"eventVersion\":2")));
                    assertThat(dead.get(1).value()).containsExactly(bytes("{broken"));
                    for (var record : dead) {
                        assertThat(text(record,"x-original-topic")).isEqualTo(userTopic);
                        assertThat(text(record,"x-consumer-service")).isEqualTo("notification-service");
                        assertThat(text(record,"x-attempt-count")).isEqualTo("1");
                    }
                }
                assertThat(admin.listOffsets(Map.of(tp,OffsetSpec.latest())).all().get().get(tp).offset()).isEqualTo(4);
                assertThat(admin.listOffsets(Map.of(new TopicPartition(USER_DLQ,0),OffsetSpec.latest())).all().get().values().iterator().next().offset()).isEqualTo(2);
            } finally {
                container.stop();
                jdbc.update("delete from notifications where event_id=?",eventId);
                jdbc.update("delete from processed_events where event_id=?",eventId);
            }
        }
    }
}
