package com.insurance.platform.auth;

import com.insurance.platform.auth.config.OutboxSchedulingConfiguration;
import com.insurance.platform.auth.event.AuthCorrelationId;
import com.insurance.platform.auth.repository.OutboxPollingRepository;
import com.insurance.platform.auth.service.AuthOutboxPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthOutboxConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
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
        var correlation = new AuthCorrelationId();
        assertThat(UUID.fromString(correlation.current())).isNotNull();
        var request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", "x".repeat(128));
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        try { assertThat(correlation.current()).isEqualTo("x".repeat(128)); }
        finally { RequestContextHolder.resetRequestAttributes(); }
    }
    @Test void publisherUsesConfiguredUserTopic() {
        context.withPropertyValues("outbox.publisher.enabled=true", "outbox.publisher.poll-interval=600000",
                "user.events.topic=custom.user.events").run(c -> {
            var repository = c.getBean(OutboxPollingRepository.class);
            var batch = mock(OutboxPollingRepository.Batch.class);
            var event = new OutboxPollingRepository.PendingEvent(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID().toString(), "UserRegistered", "{}", "test", 0);
            when(repository.claim(50)).thenReturn(Optional.of(batch));
            when(batch.events()).thenReturn(List.of(event));
            @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka = c.getBean(KafkaTemplate.class);
            when(kafka.send("custom.user.events", event.aggregateId(), event.payload()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            c.getBean(AuthOutboxPublisher.class).poll();
            verify(kafka).send("custom.user.events", event.aggregateId(), event.payload());
            verify(batch).published(eq(event.id()), any());
        });
    }
}
