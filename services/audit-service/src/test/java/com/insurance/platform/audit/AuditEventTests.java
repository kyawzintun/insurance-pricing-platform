package com.insurance.platform.audit;

import com.insurance.platform.audit.event.*;
import com.insurance.platform.audit.entity.AuditRecord;
import com.insurance.platform.audit.exception.InvalidQuoteEventException;
import com.insurance.platform.audit.repository.AuditRecordRepository;
import com.insurance.platform.audit.service.AuditService;
import java.time.*;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import static com.insurance.platform.audit.AuditFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditEventTests {
    final QuoteEventDecoder decoder = new QuoteEventDecoder();

    @Test void mapsContractAndPersistsExpectedFieldsUsingClock() {
        var event = decoder.decode(json()).orElseThrow();
        var repository = mock(AuditRecordRepository.class);
        var markers = mock(com.insurance.platform.audit.repository.ProcessedEventRepository.class);
        when(markers.tryMarkProcessed(EVENT, AuditService.CONSUMER_NAME, NOW)).thenReturn(true);
        new AuditService(repository, Clock.fixed(NOW, ZoneOffset.UTC), markers).create(event);
        verify(markers).tryMarkProcessed(EVENT, AuditService.CONSUMER_NAME, NOW);
        verify(repository).existsByEventId(EVENT);
        var row = ArgumentCaptor.forClass(AuditRecord.class);
        verify(repository).saveAndFlush(row.capture());
        var n = row.getValue();
        assertThat(n.getId()).isNotNull();
        assertThat(n.getEventId()).isEqualTo(EVENT);
        assertThat(n.getEventType()).isEqualTo("QuotePriced");
        assertThat(n.getActorType()).isEqualTo("CUSTOMER");
        assertThat(n.getActorId()).isEqualTo(CUSTOMER.toString());
        assertThat(n.getEntityType()).isEqualTo("Quote");
        assertThat(n.getEntityId()).isEqualTo(QUOTE.toString());
        assertThat(n.getCorrelationId()).isEqualTo("phase14-test");
        assertThat(n.getProducer()).isEqualTo("quote-service");
        assertThat(n.getOccurredAt()).isEqualTo(Instant.parse("2026-10-12T09:00:00Z"));
        assertThat(n.getCreatedAt()).isEqualTo(NOW);
        assertMetadata(n.getMetadata());
        verifyNoMoreInteractions(repository);
    }
    @Test void preservesDecimalMoneyAndRejectsTrailingJson() {
        var event = decoder.decode(json().replace("11200.00", "9999999999999.99")).orElseThrow();
        assertThat(event.data().finalPremium()).isEqualByComparingTo("9999999999999.99");
        assertThatThrownBy(() -> decoder.decode(json() + " {}"))
                .isInstanceOf(InvalidQuoteEventException.class).hasMessage("MALFORMED_JSON");
    }
    @Test void unsupportedTypeIsSkippedWithoutMappingItsDifferentPayload() {
        var service = mock(AuditService.class);
        var listener = new QuoteAuditListener(decoder, service);
        listener.receive(record("{\"eventType\":\"FutureEvent\",\"data\":{}}"));
        verifyNoInteractions(service);
    }
    static Stream<String> invalid() {
        return Stream.of("not json", "null", "[]", "{}", json().replace("\"eventId\":\"" + EVENT + "\",", ""),
            json().replace("\"aggregateId\":\"" + QUOTE + "\",", ""),
            json().replace("\"occurredAt\":\"2026-10-12T09:00:00Z\",", ""),
            json().replace("\"producer\":\"quote-service\",", ""),
            json().replace("\"eventVersion\":1", "\"eventVersion\":2"),
            json().replace("\"eventVersion\":1", "\"eventVersion\":4294967297"),
            json().replace("\"aggregateId\":\"" + QUOTE, "\"aggregateId\":\"" + CUSTOMER),
            json().replace("\"customerId\":\"" + CUSTOMER + "\"", "\"customerId\":null"),
            json().replace("11200.00", "null"), json().replace("11200.00", "-1"),
            json().replace("11200.00", "1.001"), json().replace("\"THB\"", "null"),
            json().replace("\"PRICED\"", "\"FAILED\""), json().replace("phase14-test", "bad id"));
    }
    @ParameterizedTest @MethodSource("invalid") void rejectsInvalidInputWithoutPersistence(String json) {
        var service = mock(AuditService.class);
        assertThatThrownBy(() -> new QuoteAuditListener(decoder, service).receive(record(json)))
                .isInstanceOf(InvalidQuoteEventException.class);
        verifyNoInteractions(service);
    }
    @Test void distinguishesMalformedJsonAndUnsupportedVersion() {
        assertThatThrownBy(() -> decoder.decode("{" )).hasMessage("MALFORMED_JSON");
        assertThatThrownBy(() -> decoder.decode(json().replace("\"eventVersion\":1", "\"eventVersion\":2")))
                .hasMessage("UNSUPPORTED_EVENT_VERSION");
    }
    @Test void databaseFailurePropagatesSafelyAndDoesNotLogExceptionPayload() {
        var service = mock(AuditService.class);
        doThrow(new IllegalStateException("secret-payload-marker")).when(service).create(any());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(QuoteAuditListener.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> new QuoteAuditListener(decoder, service).receive(record(json())))
                    .hasMessage("PERSISTENCE_FAILURE").hasNoCause();
            assertThat(appender.list).allSatisfy(log -> {
                assertThat(log.getFormattedMessage()).doesNotContain("secret-payload-marker", "11200", "Q-TEST-123");
                assertThat(log.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); }
    }
    @Test void keyMismatchIsDiagnosticAndDoesNotPreventValidEventProcessing() {
        var service = mock(AuditService.class);
        new QuoteAuditListener(decoder, service).receive(new ConsumerRecord<>("insurance.quote.events", 1, 2, "wrong-key", json()));
        verify(service).create(decoder.decode(json()).orElseThrow());
    }
    static void assertMetadata(String value) {
        var data = tools.jackson.databind.json.JsonMapper.builder().build().readTree(value);
        assertThat(data.size()).isEqualTo(7);
        assertThat(data.path("quoteReference").asText()).isEqualTo("Q-TEST-123");
        assertThat(data.path("status").asText()).isEqualTo("PRICED");
        assertThat(data.path("coverageType").asText()).isEqualTo("COMPREHENSIVE");
        assertThat(data.path("finalPremium").isNumber()).isTrue();
        assertThat(data.path("finalPremium").decimalValue()).isEqualByComparingTo("11200.00");
        assertThat(data.path("currency").asText()).isEqualTo("THB");
        assertThat(data.path("pricedAt").asText()).isEqualTo("2026-10-12T09:00:00Z");
        assertThat(data.path("expiresAt").asText()).isEqualTo("2026-11-11T09:00:00Z");
        assertThat(value).doesNotContain("password", "Authorization", "JWT", "dateOfBirth", "customerId", "secret");
    }
    private ConsumerRecord<String, String> record(String json) {
        return new ConsumerRecord<>("insurance.quote.events", 1, 2, QUOTE.toString(), json);
    }
}
