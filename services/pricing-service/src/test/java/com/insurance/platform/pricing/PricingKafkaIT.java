package com.insurance.platform.pricing;

import com.insurance.platform.pricing.service.*;
import com.insurance.platform.pricing.dto.*;
import com.insurance.platform.pricing.enums.*;
import com.insurance.platform.pricing.repository.OutboxPollingRepository;
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
class PricingKafkaIT {
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17.11-bookworm");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DATABASE::getJdbcUrl);r.add("spring.datasource.username", DATABASE::getUsername);
        r.add("spring.datasource.password", DATABASE::getPassword);r.add("auth.jwt.secret",AdminJwtSupport::secret);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
    @Autowired PricingRuleAdminService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource datasource;
    @Autowired KafkaTemplate<String,String> kafka;
    CreatePricingRuleRequest request() { return new CreatePricingRuleRequest(RuleType.DRIVER_AGE,RuleOperator.LESS_THAN,"21",null,new java.math.BigDecimal("1.30"),null,Instant.now().plusSeconds(3600),false,"Disposable test"); }
    @Test void exactStoredPublicationPerRuleOrderingAndBrokerRecovery() throws Exception {
        PricingTestIdentity.authenticate();
        try { validate(); } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    void validate() throws Exception {
        String topic="insurance.pricing.events";
        try(var admin=Admin.create(Map.of("bootstrap.servers",KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic,3,(short)1),new NewTopic(topic+".dlq",3,(short)1))).all().get(20,TimeUnit.SECONDS);
        }
        var poller=new OutboxPollingRepository(datasource);
        var publisher=new PricingOutboxPublisher(poller,kafka,Clock.systemUTC(),topic,50);
        try(var consumer=new KafkaConsumer<String,String>(Map.of("bootstrap.servers",KAFKA.getBootstrapServers(),
                "key.deserializer",StringDeserializer.class,"value.deserializer",StringDeserializer.class,"enable.auto.commit",false))) {
            var parts=List.of(new TopicPartition(topic,0),new TopicPartition(topic,1),new TopicPartition(topic,2));
            consumer.assign(parts);consumer.seekToBeginning(parts);
            var rule=service.create(request());
            service.update(rule.id(),new UpdatePricingRuleRequest(rule.ruleType(),rule.operator(),"22",null,rule.factor(),null,rule.effectiveFrom(),false,rule.description(),rule.version()));
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where status='PENDING'",Integer.class)).isEqualTo(2);
            var stored=jdbc.queryForList("select payload::text from outbox_events order by created_at,id",String.class);
            publisher.poll();
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where status='PUBLISHED' and published_at is not null",Integer.class)).isEqualTo(2);
            var records=read(consumer,2);
            assertThat(records).hasSize(2);
            assertThat(records).extracting(ConsumerRecord::value).containsExactlyElementsOf(stored);
            for(var record:records) { assertThat(record.key()).isEqualTo(rule.id().toString());assertThat(record.headers().toArray()).isEmpty(); }
            assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
            assertThat(records.get(1).offset()).isGreaterThan(records.get(0).offset());
            var ends=consumer.endOffsets(parts);publisher.poll();assertThat(consumer.endOffsets(parts)).isEqualTo(ends);
            var docker=KAFKA.getDockerClient();docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
            PricingRuleResponse outageRule;
            String payload;
            try {
                outageRule=service.create(request());
                payload=jdbc.queryForObject("select payload::text from outbox_events where aggregate_id=?",String.class,outageRule.id().toString());
                publisher.poll();
                assertThat(jdbc.queryForObject("select status from outbox_events where aggregate_id=?",String.class,outageRule.id().toString())).isEqualTo("PENDING");
                assertThat(jdbc.queryForObject("select retry_count from outbox_events where aggregate_id=?",Integer.class,outageRule.id().toString())).isEqualTo(1);
            } finally { docker.unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
            publisher.poll();
            assertThat(jdbc.queryForObject("select status from outbox_events where aggregate_id=?",String.class,outageRule.id().toString())).isEqualTo("PUBLISHED");
            for(var record:read(consumer,1)) { assertThat(record.value()).isEqualTo(payload);assertThat(record.key()).isEqualTo(outageRule.id().toString()); }
        }
    }
    List<ConsumerRecord<String,String>> read(KafkaConsumer<String,String> consumer,int count) {
        var records=new ArrayList<ConsumerRecord<String,String>>();
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(records.size()<count && System.nanoTime()<deadline) consumer.poll(Duration.ofMillis(100)).forEach(records::add);
        assertThat(records).hasSizeGreaterThanOrEqualTo(count);return records;
    }
}
