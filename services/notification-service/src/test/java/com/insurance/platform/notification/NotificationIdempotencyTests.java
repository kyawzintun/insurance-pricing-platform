package com.insurance.platform.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.insurance.platform.notification.event.QuoteEventDecoder;
import com.insurance.platform.notification.event.QuoteEventListener;
import com.insurance.platform.notification.event.QuotePricedEnvelope;
import com.insurance.platform.notification.repository.NotificationRepository;
import com.insurance.platform.notification.repository.ProcessedEventRepository;
import com.insurance.platform.notification.service.NotificationService;
import com.insurance.platform.notification.service.ProcessResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static com.insurance.platform.notification.NotificationFixtures.EVENT;
import static com.insurance.platform.notification.NotificationFixtures.NOW;
import static com.insurance.platform.notification.NotificationFixtures.QUOTE;
import static com.insurance.platform.notification.NotificationFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationIdempotencyTests {
    final NotificationRepository business = mock(NotificationRepository.class);
    final ProcessedEventRepository markers = mock(ProcessedEventRepository.class);
    final NotificationService service = new NotificationService(business, Clock.fixed(NOW, ZoneOffset.UTC), markers);
    final QuotePricedEnvelope event = new QuoteEventDecoder().decode(json()).orElseThrow();

    @Test void firstEventClaimsBeforeBusinessWrite() {
        when(markers.tryMarkProcessed(EVENT, NotificationService.CONSUMER_NAME, NOW)).thenReturn(true);
        assertThat(service.create(event)).isEqualTo(ProcessResult.PROCESSED);
        var order = inOrder(markers, business);
        order.verify(markers).tryMarkProcessed(EVENT, NotificationService.CONSUMER_NAME, NOW);
        order.verify(business).existsByEventId(EVENT);
        order.verify(business).saveAndFlush(any());
    }
    @Test void duplicateDoesNotQueryOrMutateBusinessRow() {
        assertThat(service.create(event)).isEqualTo(ProcessResult.DUPLICATE);
        verify(markers).tryMarkProcessed(EVENT, NotificationService.CONSUMER_NAME, NOW);
        verifyNoInteractions(business);
    }
    @Test void historicalRowIsRecognizedOnlyAfterClaim() {
        when(markers.tryMarkProcessed(EVENT, NotificationService.CONSUMER_NAME, NOW)).thenReturn(true);
        when(business.existsByEventId(EVENT)).thenReturn(true);
        assertThat(service.create(event)).isEqualTo(ProcessResult.DUPLICATE);
        verify(business, never()).saveAndFlush(any());
    }
    @Test void markerFailurePreventsBusinessMutation() {
        when(markers.tryMarkProcessed(any(), any(), any())).thenThrow(new IllegalStateException("marker failed"));
        assertThatThrownBy(() -> service.create(event)).hasMessage("marker failed");
        verifyNoInteractions(business);
    }
    @Test void businessFailurePropagatesForTransactionRollback() {
        when(markers.tryMarkProcessed(any(), any(), any())).thenReturn(true);
        when(business.saveAndFlush(any())).thenThrow(new IllegalStateException("business failed"));
        assertThatThrownBy(() -> service.create(event)).hasMessage("business failed");
    }
    @Test void listenerTreatsDuplicateAsSuccessAndLogsOnlyMetadata() {
        var processing = mock(NotificationService.class);
        when(processing.create(any())).thenReturn(ProcessResult.DUPLICATE);
        var logger = (Logger) LoggerFactory.getLogger(QuoteEventListener.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            new QuoteEventListener(new QuoteEventDecoder(), processing).receive(new ConsumerRecord<>("insurance.quote.events", 1, 4, QUOTE.toString().getBytes(StandardCharsets.UTF_8), json().getBytes(StandardCharsets.UTF_8)));
            assertThat(logs.list).anySatisfy(log -> assertThat(log.getFormattedMessage())
                    .contains("DUPLICATE", EVENT.toString(), "consumerName=notification-service", "partition=1", "offset=4")
                    .doesNotContain("11200", "Q-TEST-123"));
        } finally { logger.detachAppender(logs); }
    }
}
