package com.insurance.platform.quote;

import com.insurance.platform.quote.dto.QuoteResponse;
import com.insurance.platform.quote.enums.*;
import com.insurance.platform.quote.event.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import tools.jackson.databind.json.JsonMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import static com.insurance.platform.quote.QuoteFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuoteEventTests {
    static QuoteResponse response() {
        return new QuoteResponse(UUID.randomUUID(), "Q-test", QuoteStatus.PRICED, CoverageType.COMPREHENSIVE,
                null, null, new QuoteResponse.Pricing(price().basePremium(), price().finalPremium(), "THB", NOW, List.of()),
                NOW, NOW, NOW.plusSeconds(2592000));
    }
    private final QuoteCorrelationId correlation = new QuoteCorrelationId();
    private final QuotePricedEventFactory factory = new QuotePricedEventFactory(Clock.fixed(NOW, ZoneOffset.UTC), correlation);
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test void buildsStableEnvelopeWithUniqueIdClockAndMinimalData() {
        var request = new MockHttpServletRequest(); request.addHeader("X-Correlation-ID", "phase12-test-correlation");
        request.addHeader("Authorization", "Bearer private-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var quote = response(); var event = factory.create(CUSTOMER, quote).envelope();
        assertThat(event.eventId()).isNotEqualTo(factory.create(CUSTOMER, quote).envelope().eventId());
        assertThat(event.eventType()).isEqualTo("QuotePriced"); assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.aggregateType()).isEqualTo("Quote"); assertThat(event.aggregateId()).isEqualTo(quote.id());
        assertThat(event.occurredAt()).isEqualTo(NOW); assertThat(event.producer()).isEqualTo("quote-service");
        assertThat(event.correlationId()).isEqualTo("phase12-test-correlation");
        assertThat(event.data()).isEqualTo(new QuotePricedEventData(quote.id(), quote.quoteReference(), CUSTOMER,
                QuoteStatus.PRICED, quote.coverageType(), quote.pricing().finalPremium(), "THB", quote.pricedAt(), quote.expiresAt()));
        try (var serializer = new JacksonJsonSerializer<EventEnvelope<QuotePricedEventData>>()) {
            serializer.configure(Map.of(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, false), false);
            var headers = new RecordHeaders();
            String json = new String(serializer.serialize("insurance.quote.events", headers, event), StandardCharsets.UTF_8);
            var tree = JsonMapper.builder().build().readTree(json);
            assertThat(tree.size()).isEqualTo(9); assertThat(tree.get("data").size()).isEqualTo(9);
            assertThat(tree.get("occurredAt").asText()).isEqualTo(NOW.toString());
            assertThat(tree.get("data").get("finalPremium").decimalValue()).isEqualByComparingTo("11200.00");
            assertThat(json).doesNotContain("password", "private-token", "Authorization", "secret", "dateOfBirth", "@class", "com.insurance");
            assertThat(headers.toArray()).isEmpty();
        }
    }
    @Test void invalidOrMissingCorrelationGeneratesUuidWithoutEchoingInput() {
        assertThat(UUID.fromString(correlation.current())).isNotNull();
        for (String value : List.of("bad value\r\n", "x".repeat(129))) {
            var request = new MockHttpServletRequest(); request.addHeader("X-Correlation-ID", value);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            assertThat(UUID.fromString(correlation.current())).isNotNull();
        }
    }
    @Test void unpricedSnapshotsAreRejected() {
        var quote = response();
        var failed = new QuoteResponse(quote.id(), quote.quoteReference(), QuoteStatus.FAILED, quote.coverageType(),
                null, null, null, NOW, null, null);
        assertThatThrownBy(() -> factory.create(CUSTOMER, failed)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void topicAndKeyAreExplicitAndFailureLogsExcludePayloadAndExceptionText() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, EventEnvelope<QuotePricedEventData>> kafka = mock(KafkaTemplate.class);
        var publisher = new QuoteEventPublisher(kafka, "insurance.quote.events");
        var event = factory.create(CUSTOMER, response()); var envelope = event.envelope();
        var logger = (Logger) LoggerFactory.getLogger(QuoteEventPublisher.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("private-token password payload")));
            assertThatCode(() -> publisher.afterCommit(event)).doesNotThrowAnyException();
            verify(kafka).send("insurance.quote.events", envelope.aggregateId().toString(), envelope);
            assertThat(envelope.aggregateId()).isNotEqualTo(envelope.eventId()).isNotEqualTo(CUSTOMER);
            when(kafka.send(anyString(), anyString(), any())).thenThrow(new IllegalStateException("private-token"));
            assertThatCode(() -> publisher.afterCommit(event)).doesNotThrowAnyException();
            assertThat(appender.list).hasSize(2);
            for (var log : appender.list) {
                assertThat(log.getFormattedMessage()).contains("QuotePriced", envelope.eventId().toString(), envelope.aggregateId().toString(), "insurance.quote.events", envelope.correlationId())
                        .doesNotContain("private-token", "password", "11200", CUSTOMER.toString());
                assertThat(log.getThrowableProxy()).isNull();
            }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
