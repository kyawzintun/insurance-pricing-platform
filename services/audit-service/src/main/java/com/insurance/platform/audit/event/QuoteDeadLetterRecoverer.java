package com.insurance.platform.audit.event;

import com.insurance.platform.audit.exception.InvalidQuoteEventException;
import com.insurance.platform.audit.exception.InvalidUserEventException;
import com.insurance.platform.audit.exception.InvalidPricingEventException;
import com.insurance.platform.audit.exception.QuoteProcessingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.RetryListener;
import tools.jackson.databind.json.JsonMapper;

/** Framework recovery callback, not a retry loop. Return only after the DLQ broker acknowledges. */
public class QuoteDeadLetterRecoverer implements ConsumerRecordRecoverer, RetryListener {
    public static final String DLQ = "insurance.quote.events.dlq";
    private static final String ATTEMPT = "x-local-processing-attempt";
    private static final Logger LOG = LoggerFactory.getLogger(QuoteDeadLetterRecoverer.class);
    private static final Set<String> INVALID_REASONS = Set.of("MALFORMED_JSON", "MALFORMED_UTF8", "INVALID_ENVELOPE",
            "INVALID_EVENT_TYPE", "UNSUPPORTED_EVENT_VERSION", "INVALID_DATA", "INVALID_CORRELATION_ID",
            "INVALID_QUOTE_ID", "INVALID_REFERENCE", "INVALID_PREMIUM");
    private final KafkaTemplate<byte[], byte[]> kafka;
    private final Clock clock;
    private final String group;
    private final int maxAttempts;
    private final String userTopic;
    private final String userDlqTopic;
    private final String pricingTopic;
    private final String pricingDlqTopic;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public QuoteDeadLetterRecoverer(
            KafkaTemplate<byte[], byte[]> kafka,
            Clock clock,
            String group,
            int maxAttempts,
            String userTopic,
            String userDlqTopic,
            String pricingTopic,
            String pricingDlqTopic) {
        this.kafka = kafka;
        this.clock = clock;
        this.group = group;
        this.maxAttempts = maxAttempts;
        this.userTopic = userTopic;
        this.userDlqTopic = userDlqTopic;
        this.pricingTopic = pricingTopic;
        this.pricingDlqTopic = pricingDlqTopic;
    }

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception failure, int attempt) {
        // This internal header is replaced on each failed delivery, never trusted from the producer or copied to DLQ.
        record.headers().remove(ATTEMPT);
        record.headers().add(ATTEMPT, Integer.toString(attempt).getBytes(StandardCharsets.UTF_8));
        var metadata = metadata(record, failure);
        LOG.warn("Consumer processing failed consumerService=audit-service topic={} partition={} offset={} attempt={} eventId={} correlationId={} exceptionClass={} reason={}",
                record.topic(), record.partition(), record.offset(), attempt,
                metadata.eventId(), metadata.correlationId(), metadata.failureClass(), metadata.reason());
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception failure) {
        var metadata = metadata(record, failure);
        int attempt = attempts(record, failure);
        var headers = new RecordHeaders();
        add(headers, "x-original-topic", record.topic());
        add(headers, "x-original-partition", Integer.toString(record.partition()));
        add(headers, "x-original-offset", Long.toString(record.offset()));
        if (record.key() != null) headers.add("x-original-key", (byte[]) record.key());
        add(headers, "x-consumer-group", group);
        add(headers, "x-consumer-service", "audit-service");
        add(headers, "x-exception-class", metadata.failureClass());
        add(headers, "x-error-message", metadata.reason());
        add(headers, "x-failed-at", clock.instant().toString());
        add(headers, "x-attempt-count", Integer.toString(attempt));
        add(headers, "x-event-id", metadata.eventId());
        add(headers, "x-correlation-id", metadata.correlationId());
        // Fresh allowlisted headers: do not forward credentials, spoofed metadata, or old stack traces.
        // Phase 1 source and DLQ both have three partitions; preserve the source partition.
        var dlq = pricingTopic.equals(record.topic()) ? pricingDlqTopic : userTopic.equals(record.topic()) ? userDlqTopic : DLQ;
        var output = new ProducerRecord<byte[], byte[]>(dlq, record.partition(), null,
                (byte[]) record.key(), (byte[]) record.value(), headers);
        try {
            kafka.send(output).get(7, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DLQ_PUBLICATION_INTERRUPTED");
        } catch (Exception sendFailure) {
            LOG.warn("DLQ publication failed consumerService=audit-service topic={} partition={} offset={} dlqTopic={} attempt={} exceptionClass={}; source remains replayable",
                    record.topic(), record.partition(), record.offset(), dlq, attempt, sendFailure.getClass().getName());
            // No nested Kafka exception: it may contain record contents. Throwing prevents source completion.
            throw new IllegalStateException("DLQ_PUBLICATION_FAILED");
        }
        LOG.info("DLQ publication acknowledged consumerService=audit-service topic={} partition={} offset={} dlqTopic={} attempt={}",
                record.topic(), record.partition(), record.offset(), dlq, attempt);
    }

    private int attempts(ConsumerRecord<?, ?> record, Exception failure) {
        if (maxAttempts == 1 || (root(failure) instanceof InvalidQuoteEventException || root(failure) instanceof InvalidUserEventException || root(failure) instanceof InvalidPricingEventException)) return 1;
        var header = record.headers().lastHeader(ATTEMPT);
        return header == null ? 1 : Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8));
    }

    private Metadata metadata(ConsumerRecord<?, ?> record, Exception failure) {
        String eventId = null, correlationId = null;
        try {
            var json = mapper.readTree((byte[]) record.value());
            String candidate = json.path("eventId").asText();
            if (candidate.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) eventId = candidate;
            candidate = json.path("correlationId").asText();
            if (candidate.matches("[A-Za-z0-9._-]{1,128}")) correlationId = candidate;
        } catch (RuntimeException ignored) { /* Malformed input has only record coordinates. */ }
        var cause = root(failure);
        String reason = (cause instanceof InvalidQuoteEventException || cause instanceof InvalidUserEventException || cause instanceof InvalidPricingEventException) && INVALID_REASONS.contains(cause.getMessage())
                ? cause.getMessage() : "PROCESSING_FAILURE";
        String failureClass = cause instanceof QuoteProcessingException processing
                ? processing.failureClass() : cause.getClass().getName();
        return new Metadata(eventId, correlationId, failureClass, reason);
    }
    private Throwable root(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
    private void add(RecordHeaders headers, String name, String value) {
        if (value != null) headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }
    private record Metadata(String eventId, String correlationId, String failureClass, String reason) {}
}
