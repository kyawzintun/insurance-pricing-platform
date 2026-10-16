package com.insurance.platform.audit;

import com.insurance.platform.audit.event.*;
import com.insurance.platform.audit.repository.*;
import com.insurance.platform.audit.service.*;
import java.time.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import static com.insurance.platform.audit.AuditFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditIdempotencyTests {
    final AuditRecordRepository business = mock(AuditRecordRepository.class);
    final ProcessedEventRepository markers = mock(ProcessedEventRepository.class);
    final AuditService service = new AuditService(business, Clock.fixed(NOW, ZoneOffset.UTC), markers);
    final QuotePricedEnvelope event = new QuoteEventDecoder().decode(json()).orElseThrow();

    @Test void firstEventClaimsBeforeBusinessWrite() {
        when(markers.tryMarkProcessed(EVENT, AuditService.CONSUMER_NAME, NOW)).thenReturn(true);
        assertThat(service.create(event)).isEqualTo(ProcessResult.PROCESSED);
        var order = inOrder(markers, business);
        order.verify(markers).tryMarkProcessed(EVENT, AuditService.CONSUMER_NAME, NOW);
        order.verify(business).existsByEventId(EVENT);
        order.verify(business).saveAndFlush(any());
    }
    @Test void duplicateDoesNotQueryOrMutateBusinessRow() {
        assertThat(service.create(event)).isEqualTo(ProcessResult.DUPLICATE);
        verify(markers).tryMarkProcessed(EVENT, AuditService.CONSUMER_NAME, NOW);
        verifyNoInteractions(business);
    }
    @Test void historicalRowIsRecognizedOnlyAfterClaim() {
        when(markers.tryMarkProcessed(EVENT, AuditService.CONSUMER_NAME, NOW)).thenReturn(true);
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
        var processing = mock(AuditService.class);
        when(processing.create(any())).thenReturn(ProcessResult.DUPLICATE);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(QuoteAuditListener.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            new QuoteAuditListener(new QuoteEventDecoder(), processing).receive(new ConsumerRecord<>("insurance.quote.events", 1, 4, QUOTE.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), json().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(logs.list).anySatisfy(log -> assertThat(log.getFormattedMessage())
                    .contains("DUPLICATE", EVENT.toString(), "consumerName=audit-service", "partition=1", "offset=4")
                    .doesNotContain("11200", "Q-TEST-123"));
        } finally { logger.detachAppender(logs); }
    }
}
