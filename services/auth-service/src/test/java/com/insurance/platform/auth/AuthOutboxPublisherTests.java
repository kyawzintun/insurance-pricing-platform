package com.insurance.platform.auth;

import com.insurance.platform.auth.repository.OutboxPollingRepository;
import com.insurance.platform.auth.repository.OutboxPollingRepository.*;
import com.insurance.platform.auth.service.AuthOutboxPublisher;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthOutboxPublisherTests {
    private final OutboxPollingRepository repository = mock(OutboxPollingRepository.class);
    private final Batch batch = mock(Batch.class);
    @SuppressWarnings("unchecked") private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC);
    private final AuthOutboxPublisher publisher = new AuthOutboxPublisher(repository, kafka, clock, "insurance.user.events", 7);
    private final PendingEvent event = new PendingEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(),
            "UserRegistered", "{\"eventId\":\"stored-event\"}", "original-correlation", 2);
    private void claim() throws Exception {
        when(repository.claim(7)).thenReturn(Optional.of(batch));
        when(batch.events()).thenReturn(List.of(event));
    }
    @Test void exactPayloadAndUserKeyAreSentAndOnlyAcknowledgmentAllowsPublished() throws Exception {
        claim();
        var future = new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
        when(kafka.send("insurance.user.events", event.aggregateId(), event.payload())).thenAnswer(call -> {
            verify(batch, never()).published(any(), any());
            future.complete(null);
            return future;
        });
        publisher.poll();
        var order = inOrder(repository, kafka, batch);
        order.verify(repository).claim(7);
        order.verify(kafka).send("insurance.user.events", event.aggregateId(), event.payload());
        order.verify(batch).published(event.id(), Instant.parse("2026-10-17T00:00:00Z"));
        order.verify(batch).close();
        verify(batch, never()).failed(any());
    }
    @Test void asynchronousFailureLeavesPendingAndIncrementsFailureCount() throws Exception {
        claim();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("private payload")));
        publisher.poll();
        verify(batch).failed(event.id()); verify(batch, never()).published(any(), any()); verify(batch).close();
    }
    @Test void synchronousEnqueueFailureAlsoLeavesPending() throws Exception {
        claim(); when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException());
        publisher.poll();
        verify(batch).failed(event.id()); verify(batch, never()).published(any(), any()); verify(batch).close();
    }
    @Test void failedStatusUpdateDoesNotCountAsFailedKafkaAttemptAndReleasesLock() throws Exception {
        claim(); when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        doThrow(new SQLException("private payload")).when(batch).published(any(), any());
        publisher.poll();
        verify(batch, never()).failed(any()); verify(batch).close();
    }
    @Test void emptyBatchIsHarmlessAndReleasesLock() throws Exception {
        claim(); when(batch.events()).thenReturn(List.of()); publisher.poll();
        verifyNoInteractions(kafka); verify(batch).close();
    }
    @Test void competingPublisherDoesNoWork() throws Exception {
        when(repository.claim(7)).thenReturn(Optional.empty()); publisher.poll(); verifyNoInteractions(kafka, batch);
    }
    @Test void batchSizeIsValidated() {
        for (int size : List.of(0, -1, 501)) assertThatThrownBy(() -> new AuthOutboxPublisher(repository, kafka, clock, "topic", size))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void failureLogsContainMetadataButNeverPayloadOrExceptionText() throws Exception {
        claim(); when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("private-password"));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AuthOutboxPublisher.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            publisher.poll();
            assertThat(appender.list).singleElement().satisfies(log -> {
                assertThat(log.getFormattedMessage()).contains(event.id().toString(), event.eventId().toString(), event.aggregateId(),
                        event.eventType(), "insurance.user.events", event.correlationId(), "retryCount=2")
                        .doesNotContain("private-password", event.payload());
                assertThat(log.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    @Test void retryPreservesExactIdentityKeyAndPayload() throws Exception {
        claim();
        when(kafka.send("insurance.user.events", event.aggregateId(), event.payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException()), CompletableFuture.completedFuture(null));
        publisher.poll(); publisher.poll();
        verify(kafka, times(2)).send("insurance.user.events", event.aggregateId(), event.payload());
        verify(batch).failed(event.id()); verify(batch).published(event.id(), Instant.parse("2026-10-17T00:00:00Z"));
    }
}
