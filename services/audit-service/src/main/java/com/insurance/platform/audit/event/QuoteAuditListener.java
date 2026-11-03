package com.insurance.platform.audit.event;

import com.insurance.platform.audit.exception.InvalidQuoteEventException;
import com.insurance.platform.audit.exception.QuoteProcessingException;
import com.insurance.platform.audit.service.AuditService;
import com.insurance.platform.audit.service.ProcessResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class QuoteAuditListener {
    private static final Logger LOG = LoggerFactory.getLogger(QuoteAuditListener.class);
    private final QuoteEventDecoder decoder;
    private final AuditService service;

    public QuoteAuditListener(QuoteEventDecoder decoder, AuditService service) {
        this.decoder = decoder;
        this.service = service;
    }

    private EventObservation observation = new EventObservation(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    @org.springframework.beans.factory.annotation.Autowired
    public void configureObservation(EventObservation observation) { this.observation = observation; }

    @KafkaListener(id = "quote-audit", idIsGroup = false,
            topics = "${audit.events.topic:insurance.quote.events}")
    public void receive(ConsumerRecord<byte[], byte[]> record) {
        QuotePricedEnvelope event;
        try {
            var decoded = decoder.decode(record.value());
            if (decoded.isEmpty()) {
                LOG.info("Ignored unsupported event type topic={} partition={} offset={}", record.topic(), record.partition(), record.offset());
                return; // Intentional skip: RECORD mode commits this unsupported event's offset.
            }
            event = decoded.get();
        } catch (InvalidQuoteEventException failure) {
            LOG.warn("Rejected quote event reason={} topic={} partition={} offset={}; error handler will classify this failure",
                    failure.getMessage(), record.topic(), record.partition(), record.offset());
            throw failure;
        }

        if (!java.util.Arrays.equals(event.data().quoteId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), record.key())) {
            // The event body is authoritative. Do not echo an untrusted key.
            LOG.warn("Quote event key mismatch eventId={} quoteId={} topic={} partition={} offset={}",
                    event.eventId(), event.aggregateId(), record.topic(), record.partition(), record.offset());
        }

        ProcessResult result;
        try {
            result = observation.process(event.correlationId(), record.topic(), () -> service.create(event));
        } catch (RuntimeException failure) {
            log(event, record, "PERSISTENCE_FAILURE");
            throw new QuoteProcessingException(failure);
        }

        LOG.info("Event handled result={} eventId={} eventType={} consumerName={} quoteId={} correlationId={} topic={} partition={} offset={}",
                result, event.eventId(), event.eventType(), AuditService.CONSUMER_NAME, event.aggregateId(),
                event.correlationId(), record.topic(), record.partition(), record.offset());
    }

    private void log(QuotePricedEnvelope event, ConsumerRecord<byte[], byte[]> record, String reason) {
        LOG.warn("Audit record failed reason={} eventId={} eventType={} quoteId={} correlationId={} topic={} partition={} offset={}; error handler will classify this failure",
                reason, event.eventId(), event.eventType(), event.aggregateId(), event.correlationId(), record.topic(), record.partition(), record.offset());
    }
}
