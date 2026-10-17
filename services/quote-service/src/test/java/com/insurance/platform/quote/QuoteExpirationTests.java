package com.insurance.platform.quote;

import com.insurance.platform.quote.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuoteExpirationTests {
    @Test
    void disabledSchedulerHasNoScheduledTasks() {
        new ApplicationContextRunner().withUserConfiguration(QuoteExpirationScheduler.class)
                .withPropertyValues("quote.expiration.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(QuoteExpirationScheduler.class));
    }

    @Test
    void configuredSchedulerRegistersAndUsesBoundedBatch() {
        var service = mock(QuoteExpirationService.class);
        new ApplicationContextRunner().withUserConfiguration(QuoteExpirationScheduler.class)
                .withBean(QuoteExpirationService.class, () -> service)
                .withPropertyValues("quote.expiration.enabled=true", "quote.expiration.batch-size=7",
                        "quote.expiration.poll-interval=3600000")
                .run(context -> {
                    assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
                    context.getBean(QuoteExpirationScheduler.class).poll();
                    verify(service).expireBatch(7);
                });
    }

    @Test
    void invalidBatchRejectedAndFailureRetriedOnNextPoll() {
        var service = mock(QuoteExpirationService.class);
        assertThatThrownBy(() -> new QuoteExpirationScheduler(service, 0)).isInstanceOf(IllegalArgumentException.class);
        when(service.expireBatch(50)).thenThrow(new IllegalStateException("test")).thenReturn(1);
        var scheduler = new QuoteExpirationScheduler(service, 50);
        scheduler.poll(); scheduler.poll();
        verify(service, times(2)).expireBatch(50);
    }
}
