package com.insurance.platform.audit.event;
import com.insurance.platform.audit.service.AuditService;
import com.insurance.platform.audit.exception.QuoteProcessingException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
@Component
public class PricingEventListener {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PricingEventListener.class);
    private final PricingEventDecoder decoder;
    private final AuditService service;

    public PricingEventListener(PricingEventDecoder decoder, AuditService service) {
        this.decoder = decoder;
        this.service = service;
    }

    @KafkaListener(id = "pricing-audit", idIsGroup = false, topics = "${pricing.events.topic:insurance.pricing.events}")
    public void receive(ConsumerRecord<byte[], byte[]> record) {
        var decoded = decoder.decode(record.value());
        if (decoded.isEmpty()) {
            LOG.info("Ignored unsupported pricing event topic={} partition={} offset={}", record.topic(), record.partition(), record.offset());
            return;
        }
        try {
            var event = decoded.get();
            var result = service.recordPricing(event);
            LOG.info("Pricing event handled result={} eventId={} pricingRuleId={} correlationId={} partition={} offset={}",
                    result, event.eventId(), event.aggregateId(), event.correlationId(), record.partition(), record.offset());
        } catch (RuntimeException failure) {
            throw new QuoteProcessingException(failure);
        }
    }
}
