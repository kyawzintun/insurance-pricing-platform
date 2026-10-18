package com.insurance.platform.auth;

import com.insurance.platform.auth.config.OutboxSchedulingConfiguration;
import com.insurance.platform.auth.service.AuthOutboxPublisher;
import com.insurance.platform.auth.repository.OutboxPollingRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthOutboxConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(OutboxSchedulingConfiguration.class, AuthOutboxPublisher.class)
            .withBean(OutboxPollingRepository.class, () -> mock(OutboxPollingRepository.class))
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(Clock.class, Clock::systemUTC);

    @Test void disabledPublisherDoesNotInstallScheduler() {
        context.withPropertyValues("outbox.publisher.enabled=false").run(c -> {
            assertThat(c).doesNotHaveBean(AuthOutboxPublisher.class);
            assertThat(c).doesNotHaveBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor");
        });
    }

    @Test void configuredPublisherInstallsSchedulerAndValidatesBatchBounds() {
        context.withPropertyValues("outbox.publisher.enabled=true", "outbox.publisher.poll-interval=600000",
                "outbox.publisher.batch-size=7").run(c -> assertThat(c).hasSingleBean(AuthOutboxPublisher.class));
        context.withPropertyValues("outbox.publisher.enabled=true", "outbox.publisher.batch-size=0")
                .run(c -> assertThat(c).hasFailed());
    }

    @Test void correlationFallbackIsSafeAndExistingHeaderIsPreserved() {
        var correlation = new com.insurance.platform.auth.event.AuthCorrelationId();
        assertThat(java.util.UUID.fromString(correlation.current())).isNotNull();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", "x".repeat(128));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try { assertThat(correlation.current()).isEqualTo("x".repeat(128)); }
        finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }
    @Test void publisherUsesConfiguredUserTopic() {
        context.withPropertyValues("outbox.publisher.enabled=true", "outbox.publisher.poll-interval=600000",
                "user.events.topic=custom.user.events").run(c -> {
            var repository = c.getBean(OutboxPollingRepository.class);
            var batch = mock(OutboxPollingRepository.Batch.class);
            var event = new OutboxPollingRepository.PendingEvent(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                    java.util.UUID.randomUUID().toString(), "UserRegistered", "{}", "test", 0);
            when(repository.claim(50)).thenReturn(java.util.Optional.of(batch));
            when(batch.events()).thenReturn(java.util.List.of(event));
            @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka = c.getBean(KafkaTemplate.class);
            when(kafka.send("custom.user.events", event.aggregateId(), event.payload()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
            c.getBean(AuthOutboxPublisher.class).poll();
            verify(kafka).send("custom.user.events", event.aggregateId(), event.payload());
            verify(batch).published(eq(event.id()), any());
        });
    }
}
