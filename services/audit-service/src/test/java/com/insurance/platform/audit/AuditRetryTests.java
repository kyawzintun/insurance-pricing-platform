package com.insurance.platform.audit;

import com.insurance.platform.audit.config.AuditKafkaConfiguration;
import com.insurance.platform.audit.event.*;
import com.insurance.platform.audit.exception.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import static com.insurance.platform.audit.AuditFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditRetryTests {
    @SuppressWarnings("unchecked") final KafkaTemplate<byte[], byte[]> kafka = mock(KafkaTemplate.class);
    final QuoteDeadLetterRecoverer recoverer = new QuoteDeadLetterRecoverer(kafka, Clock.fixed(NOW, ZoneOffset.UTC), "test-group", 3, "custom.user.events", "custom.user.failures", "custom.pricing.events", "custom.pricing.failures");
    ConsumerRecord<byte[], byte[]> record(byte[] value) {
        return new ConsumerRecord<>("insurance.quote.events", 1, 42, QUOTE.toString().getBytes(StandardCharsets.UTF_8), value);
    }
    @Test void preservesRawBytesAndAddsOnlySafeFailureHeaders() {
        byte[] malformed = {(byte) 0xff, '{', (byte) 0xc3};
        var input = record(malformed);
        input.headers().add("Authorization", "secret-token".getBytes(StandardCharsets.UTF_8));
        input.headers().add("x-attempt-count", "999".getBytes(StandardCharsets.UTF_8));
        input.headers().add("x-exception-class", "spoofed".getBytes(StandardCharsets.UTF_8));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        recoverer.accept(input, new InvalidQuoteEventException("MALFORMED_UTF8"));
        var sent = ArgumentCaptor.forClass(ProducerRecord.class); verify(kafka).send(sent.capture());
        var output = sent.getValue();
        assertThat(output.topic()).isEqualTo("insurance.quote.events.dlq");
        assertThat(output.partition()).isEqualTo(1);
        assertThat((byte[]) output.key()).containsExactly(input.key());
        assertThat((byte[]) output.value()).containsExactly(malformed);
        assertThat(text(output, "x-consumer-service")).isEqualTo("audit-service");
        assertThat(text(output, "x-consumer-group")).isEqualTo("test-group");
        assertThat(text(output, "x-original-topic")).isEqualTo(input.topic());
        assertThat(text(output, "x-original-partition")).isEqualTo("1");
        assertThat(text(output, "x-original-offset")).isEqualTo("42");
        assertThat(text(output, "x-attempt-count")).isEqualTo("1");
        assertThat(text(output, "x-error-message")).isEqualTo("MALFORMED_UTF8");
        assertThat(text(output, "x-failed-at")).isEqualTo(NOW.toString());
        assertThat(output.headers().lastHeader("Authorization")).isNull();
        assertThat(output.headers().lastHeader("x-event-id")).isNull();
        for (var header : output.headers()) assertThat(header.key()).doesNotContain("stacktrace", "exception-stack");
    }
    @Test void retryMetadataUsesActualAttemptAndSafeUnderlyingExceptionClass() {
        var input = record(json().getBytes(StandardCharsets.UTF_8));
        var failure = new QuoteProcessingException(new IllegalStateException("password-secret SQL payload"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        recoverer.failedDelivery(input, failure, 3);
        recoverer.accept(input, failure);
        var sent = ArgumentCaptor.forClass(ProducerRecord.class); verify(kafka).send(sent.capture());
        var output = sent.getValue();
        assertThat(text(output, "x-attempt-count")).isEqualTo("3");
        assertThat(text(output, "x-event-id")).isEqualTo(EVENT.toString());
        assertThat(text(output, "x-exception-class")).isEqualTo(IllegalStateException.class.getName());
        assertThat(text(output, "x-error-message")).isEqualTo("PROCESSING_FAILURE");
        for (var header : output.headers()) assertThat(new String(header.value(), StandardCharsets.UTF_8)).doesNotContain("password-secret", "SQL payload");
    }
    @Test void recoveryDoesNotReturnBeforeBrokerAcknowledgment() throws Exception {
        var send = new CompletableFuture<SendResult<byte[], byte[]>>();
        var entered = new CountDownLatch(1);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> { entered.countDown(); return send; });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> recoverer.accept(record(new byte[]{'{'}), new InvalidQuoteEventException("MALFORMED_JSON")));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> task.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { send.complete(null); }
            task.get(3, TimeUnit.SECONDS);
        }
    }
    @Test void failedDlqSendThrowsWithoutPayloadBearingCause() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("secret-payload")));
        assertThatThrownBy(() -> recoverer.accept(record(new byte[]{'{'}), new InvalidQuoteEventException("MALFORMED_JSON")))
                .hasMessage("DLQ_PUBLICATION_FAILED").hasNoCause();
    }
    @Test void synchronousDlqFailureAlsoThrows() {
        when(kafka.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("secret-payload"));
        assertThatThrownBy(() -> recoverer.accept(record(new byte[]{'{'}), new InvalidQuoteEventException("MALFORMED_JSON")))
                .hasMessage("DLQ_PUBLICATION_FAILED").hasNoCause();
    }
    @Test void invalidUtf8AndTombstoneAreNonRetryableInputFailures() {
        var decoder = new QuoteEventDecoder();
        assertThatThrownBy(() -> decoder.decode(new byte[]{(byte) 0xff})).isInstanceOf(InvalidQuoteEventException.class).hasMessage("MALFORMED_UTF8");
        assertThatThrownBy(() -> decoder.decode((byte[]) null)).isInstanceOf(InvalidQuoteEventException.class).hasMessage("INVALID_ENVELOPE");
    }
    @Test void nullKeyAndNullValueRemainNullForDlqInspection() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        recoverer.accept(new ConsumerRecord<byte[], byte[]>("insurance.quote.events", 0, 0, null, null), new InvalidQuoteEventException("INVALID_ENVELOPE"));
        var sent = ArgumentCaptor.forClass(ProducerRecord.class); verify(kafka).send(sent.capture());
        assertThat(sent.getValue().key()).isNull(); assertThat(sent.getValue().value()).isNull();
    }
    @Test void retryPolicyRejectsUnboundedConfiguration() {
        var config = new AuditKafkaConfiguration();
        assertThatThrownBy(() -> config.auditErrorHandler(recoverer, 0, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.auditErrorHandler(recoverer, 11, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.auditErrorHandler(recoverer, 3, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.auditErrorHandler(recoverer, 3, 10001)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void frameworkRetriesUnexpectedRuntimeExactlyThreeTimes() {
        var callback = mock(QuoteDeadLetterRecoverer.class);
        var handler = new AuditKafkaConfiguration().auditErrorHandler(callback, 3, 0);
        var input = record(json().getBytes(StandardCharsets.UTF_8));
        var failure = new IllegalStateException("unexpected processing failure");
        var consumer = mock(org.apache.kafka.clients.consumer.Consumer.class);
        var container = mock(org.springframework.kafka.listener.MessageListenerContainer.class);
        assertThat(handler.handleOne(failure, input, consumer, container)).isFalse();
        assertThat(handler.handleOne(failure, input, consumer, container)).isFalse();
        verify(callback, never()).accept(any(), any());
        assertThat(handler.handleOne(failure, input, consumer, container)).isTrue();
        verify(callback).accept(input, failure);
        verify(callback).failedDelivery(input, failure, 3);
    }
    @Test void frameworkImmediatelyRecoversWrappedPermanentInputFailure() {
        var callback = mock(QuoteDeadLetterRecoverer.class);
        var handler = new AuditKafkaConfiguration().auditErrorHandler(callback, 3, 0);
        var input = record(new byte[]{'{'});
        var failure = new org.springframework.kafka.listener.ListenerExecutionFailedException("safe", new InvalidQuoteEventException("MALFORMED_JSON"));
        assertThat(handler.handleOne(failure, input, mock(org.apache.kafka.clients.consumer.Consumer.class),
                mock(org.springframework.kafka.listener.MessageListenerContainer.class))).isTrue();
        verify(callback).accept(input, failure);
    }
    String text(ProducerRecord<?, ?> record, String name) { return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8); }
    @Test void configuredUserTopicRoutesToConfiguredDlqWithOriginalBytes() {
        byte[] malformed = {(byte) 0xff, '{'};
        var input = new ConsumerRecord<byte[],byte[]>("custom.user.events", 1, 42,
                QUOTE.toString().getBytes(StandardCharsets.UTF_8), malformed);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        recoverer.accept(input, new InvalidUserEventException("MALFORMED_UTF8"));
        var sent = ArgumentCaptor.forClass(ProducerRecord.class); verify(kafka).send(sent.capture());
        var output = sent.getValue();
        assertThat(output.topic()).isEqualTo("custom.user.failures");
        assertThat(output.partition()).isEqualTo(1);
        assertThat((byte[]) output.key()).containsExactly(input.key());
        assertThat((byte[]) output.value()).containsExactly(malformed);
        assertThat(text(output, "x-original-topic")).isEqualTo("custom.user.events");
        assertThat(text(output, "x-attempt-count")).isEqualTo("1");
    }
}
