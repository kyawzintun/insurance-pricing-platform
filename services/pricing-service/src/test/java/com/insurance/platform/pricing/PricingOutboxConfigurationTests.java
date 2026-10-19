package com.insurance.platform.pricing;

import com.insurance.platform.pricing.config.OutboxSchedulingConfiguration;
import com.insurance.platform.pricing.service.PricingOutboxPublisher;
import com.insurance.platform.pricing.repository.OutboxPollingRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricingOutboxConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
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
            var event = new OutboxPollingRepository.PendingEvent(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                    java.util.UUID.randomUUID().toString(), "PricingRuleCreated", "{}", "test", 0);
            when(repository.claim(50)).thenReturn(java.util.Optional.of(batch));
            when(batch.events()).thenReturn(java.util.List.of(event));
            @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka = c.getBean(KafkaTemplate.class);
            when(kafka.send("custom.pricing.events", event.aggregateId(), event.payload()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
            c.getBean(PricingOutboxPublisher.class).poll();
            verify(kafka).send("custom.pricing.events", event.aggregateId(), event.payload());
            verify(batch).published(eq(event.id()), any());
        });
    }
}
