package com.insurance.platform.quote.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class QuoteEventPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(QuoteEventPublisher.class);
    private final KafkaTemplate<String, EventEnvelope<QuotePricedEventData>> kafka;
    private final String topic;

    public QuoteEventPublisher(KafkaTemplate<String, EventEnvelope<QuotePricedEventData>> kafka,
            @Value("${quote.events.topic:insurance.quote.events}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    // No fallback execution: without a real transaction commit, nothing is published.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(QuotePricedEvent event) {
        var envelope = event.envelope();
        try {
            kafka.send(topic, envelope.aggregateId().toString(), envelope).whenComplete((result, failure) -> {
                if (failure != null) logFailure(envelope, failure);
                else LOG.info("Published eventType={} eventId={} quoteId={} topic={} correlationId={} partition={} offset={}",
                        envelope.eventType(), envelope.eventId(), envelope.aggregateId(), topic, envelope.correlationId(),
                        result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            });
        } catch (RuntimeException failure) {
            // A synchronous enqueue/metadata/serialization failure cannot undo the committed quote either.
            logFailure(envelope, failure);
        }
    }

    private void logFailure(EventEnvelope<QuotePricedEventData> event, Throwable failure) {
        // Do not log payloads, exception messages or stack traces that may embed serialized data.
        LOG.warn("Publication failed eventType={} eventId={} quoteId={} topic={} correlationId={} failureType={}; quote remains committed",
                event.eventType(), event.eventId(), event.aggregateId(), topic, event.correlationId(), failure.getClass().getSimpleName());
    }
}
