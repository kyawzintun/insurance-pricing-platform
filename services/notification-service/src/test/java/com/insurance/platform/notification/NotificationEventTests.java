package com.insurance.platform.notification;

import com.insurance.platform.notification.event.*;
import com.insurance.platform.notification.entity.Notification;
import com.insurance.platform.notification.exception.InvalidQuoteEventException;
import com.insurance.platform.notification.repository.NotificationRepository;
import com.insurance.platform.notification.service.NotificationService;
import java.time.*;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import static com.insurance.platform.notification.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationEventTests {
    final QuoteEventDecoder decoder = new QuoteEventDecoder();

    @Test void mapsContractAndPersistsExpectedFieldsUsingClock() {
        var event = decoder.decode(json()).orElseThrow();
        var repository = mock(NotificationRepository.class);
        var markers = mock(com.insurance.platform.notification.repository.ProcessedEventRepository.class);
        when(markers.tryMarkProcessed(EVENT, NotificationService.CONSUMER_NAME, NOW)).thenReturn(true);
        new NotificationService(repository, Clock.fixed(NOW, ZoneOffset.UTC), markers).create(event);
        verify(markers).tryMarkProcessed(EVENT, NotificationService.CONSUMER_NAME, NOW);
        verify(repository).existsByEventId(EVENT);
        var row = ArgumentCaptor.forClass(Notification.class);
        verify(repository).saveAndFlush(row.capture());
        var n = row.getValue();
        assertThat(n.getId()).isNotNull();
        assertThat(n.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(n.getQuoteId()).isEqualTo(QUOTE);
        assertThat(n.getEventId()).isEqualTo(EVENT);
        assertThat(n.getType()).isEqualTo("QUOTE_PRICED");
        assertThat(n.getStatus()).isEqualTo("CREATED");
        assertThat(n.getMessage()).isEqualTo("Your quote Q-TEST-123 is ready. Premium: 11200.00 THB.");
        assertThat(n.getCreatedAt()).isEqualTo(NOW);
        assertThat(n.getProcessedAt()).isEqualTo(NOW);
        verifyNoMoreInteractions(repository);
    }
    @Test void preservesDecimalMoneyAndRejectsTrailingJson() {
        var event = decoder.decode(json().replace("11200.00", "9999999999999.99")).orElseThrow();
        assertThat(event.data().finalPremium()).isEqualByComparingTo("9999999999999.99");
        assertThatThrownBy(() -> decoder.decode(json() + " {}"))
                .isInstanceOf(InvalidQuoteEventException.class).hasMessage("MALFORMED_JSON");
    }
    @Test void unsupportedTypeIsSkippedWithoutMappingItsDifferentPayload() {
        var service = mock(NotificationService.class);
        var listener = new QuoteEventListener(decoder, service);
        listener.receive(record("{\"eventType\":\"FutureEvent\",\"data\":{}}"));
        verifyNoInteractions(service);
    }
    static Stream<String> invalid() {
        return Stream.of("not json", "null", "[]", "{}", json().replace("\"eventId\":\"" + EVENT + "\",", ""),
            json().replace("\"eventVersion\":1", "\"eventVersion\":2"),
            json().replace("\"eventVersion\":1", "\"eventVersion\":4294967297"),
            json().replace("\"aggregateId\":\"" + QUOTE, "\"aggregateId\":\"" + CUSTOMER),
            json().replace("\"customerId\":\"" + CUSTOMER + "\"", "\"customerId\":null"),
            json().replace("11200.00", "null"), json().replace("11200.00", "-1"),
            json().replace("11200.00", "1.001"), json().replace("\"THB\"", "null"),
            json().replace("\"PRICED\"", "\"FAILED\""), json().replace("phase13-test", "bad id"));
    }
    @ParameterizedTest @MethodSource("invalid") void rejectsInvalidInputWithoutPersistence(String json) {
        var service = mock(NotificationService.class);
        assertThatThrownBy(() -> new QuoteEventListener(decoder, service).receive(record(json)))
                .isInstanceOf(InvalidQuoteEventException.class);
        verifyNoInteractions(service);
    }
    @Test void distinguishesMalformedJsonAndUnsupportedVersion() {
        assertThatThrownBy(() -> decoder.decode("{" )).hasMessage("MALFORMED_JSON");
        assertThatThrownBy(() -> decoder.decode(json().replace("\"eventVersion\":1", "\"eventVersion\":2")))
                .hasMessage("UNSUPPORTED_EVENT_VERSION");
    }
    @Test void databaseFailurePropagatesSafelyAndDoesNotLogExceptionPayload() {
        var service = mock(NotificationService.class);
        doThrow(new IllegalStateException("secret-payload-marker")).when(service).create(any());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(QuoteEventListener.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> new QuoteEventListener(decoder, service).receive(record(json())))
                    .hasMessage("PERSISTENCE_FAILURE").hasNoCause();
            assertThat(appender.list).allSatisfy(log -> {
                assertThat(log.getFormattedMessage()).doesNotContain("secret-payload-marker", "11200", "Q-TEST-123");
                assertThat(log.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); }
    }
    @Test void keyMismatchIsDiagnosticAndDoesNotPreventValidEventProcessing() {
        var service = mock(NotificationService.class);
        new QuoteEventListener(decoder, service).receive(new ConsumerRecord<>("insurance.quote.events", 1, 2, "wrong-key", json()));
        verify(service).create(decoder.decode(json()).orElseThrow());
    }
    private ConsumerRecord<String, String> record(String json) {
        return new ConsumerRecord<>("insurance.quote.events", 1, 2, QUOTE.toString(), json);
    }
}
