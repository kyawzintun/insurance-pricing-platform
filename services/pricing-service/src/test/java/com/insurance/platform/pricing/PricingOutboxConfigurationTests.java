package com.insurance.platform.pricing;

import com.insurance.platform.pricing.config.OutboxSchedulingConfiguration;
import com.insurance.platform.pricing.repository.OutboxPollingRepository;
import com.insurance.platform.pricing.service.PricingOutboxPublisher;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PricingOutboxConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(OutboxSchedulingConfiguration.class, PricingOutboxPublisher.class)
            .withBean(OutboxPollingRepository.class, () -> mock(OutboxPollingRepository.class))
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(Clock.class, Clock::systemUTC);

    @Test void disabledPublisherDoesNotInstallScheduler() {
        context.withPropertyValues("outbox.publisher.enabled=false").run(c -> {
            assertThat(c).doesNotHaveBean(PricingOutboxPublisher.class);
            assertThat(c).doesNotHaveBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor");
        });
    }

    @Test void configuredPublisherInstallsSchedulerAndValidatesBatchBounds() {
        context.withPropertyValues("outbox.publisher.enabled=true", "outbox.publisher.poll-interval=600000",
                "outbox.publisher.batch-size=7").run(c -> assertThat(c).hasSingleBean(PricingOutboxPublisher.class));
        context.withPropertyValues("outbox.publisher.enabled=true", "outbox.publisher.batch-size=0")
                .run(c -> assertThat(c).hasFailed());
    }

    @Test void publisherUsesConfiguredPricingTopic() {
        context.withPropertyValues("outbox.publisher.enabled=true", "outbox.publisher.poll-interval=600000",
                "pricing.events.topic=custom.pricing.events").run(c -> {
            var repository = c.getBean(OutboxPollingRepository.class);
            var batch = mock(OutboxPollingRepository.Batch.class);
            var event = new OutboxPollingRepository.PendingEvent(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID().toString(), "PricingRuleCreated", "{}", "test", 0);
            when(repository.claim(50)).thenReturn(Optional.of(batch));
            when(batch.events()).thenReturn(List.of(event));
            @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka = c.getBean(KafkaTemplate.class);
            when(kafka.send("custom.pricing.events", event.aggregateId(), event.payload()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            c.getBean(PricingOutboxPublisher.class).poll();
            verify(kafka).send("custom.pricing.events", event.aggregateId(), event.payload());
            verify(batch).published(eq(event.id()), any());
        });
    }
}
