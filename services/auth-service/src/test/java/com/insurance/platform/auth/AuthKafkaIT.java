package com.insurance.platform.auth;
import com.insurance.platform.auth.service.*;
import com.insurance.platform.auth.repository.OutboxPollingRepository;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest
@ActiveProfiles("integration")
@Testcontainers
class AuthKafkaIT {
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17.11-bookworm");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DATABASE::getJdbcUrl); r.add("spring.datasource.username", DATABASE::getUsername);
        r.add("spring.datasource.password", DATABASE::getPassword);
        r.add("auth.jwt.secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
    @Autowired AuthService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource datasource;
    @Autowired KafkaTemplate<String,String> kafka;
    @Test void registrationOutageRecoveryAndExactStoredEventPublication() throws Exception {
        String topic="insurance.user.events";
        try(var admin=Admin.create(Map.of("bootstrap.servers",KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic,3,(short)1),new NewTopic(topic+".dlq",3,(short)1))).all().get(20,TimeUnit.SECONDS);
        }
        var poller=new OutboxPollingRepository(datasource);
        var publisher=new AuthOutboxPublisher(poller,kafka,Clock.systemUTC(),topic,50);
        var docker=KAFKA.getDockerClient();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        String payload;
        java.util.UUID user;
        try {
            user=service.register(AuthPostgresIT.request("kafka@example.com")).id();
            payload=jdbc.queryForObject("select payload::text from outbox_events",String.class);
            publisher.poll();
            assertThat(jdbc.queryForObject("select status from outbox_events",String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select retry_count from outbox_events",Integer.class)).isEqualTo(1);
        } finally { docker.unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
        publisher.poll();
        assertThat(jdbc.queryForObject("select status from outbox_events",String.class)).isEqualTo("PUBLISHED");
        try(var consumer=new KafkaConsumer<String,String>(Map.of("bootstrap.servers",KAFKA.getBootstrapServers(),
                "key.deserializer",StringDeserializer.class,"value.deserializer",StringDeserializer.class,"enable.auto.commit",false))) {
            var parts=List.of(new TopicPartition(topic,0),new TopicPartition(topic,1),new TopicPartition(topic,2));
            consumer.assign(parts);consumer.seekToBeginning(parts);
            var records=new ArrayList<ConsumerRecord<String,String>>();
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(records.isEmpty() && System.nanoTime()<deadline) consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            assertThat(records).isNotEmpty();
            for(var record:records) { assertThat(record.key()).isEqualTo(user.toString());assertThat(record.value()).isEqualTo(payload); }
            var before=consumer.endOffsets(parts); publisher.poll();assertThat(consumer.endOffsets(parts)).isEqualTo(before);
        }
    }
}
