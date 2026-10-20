package com.insurance.platform.audit.event;

import com.insurance.platform.audit.exception.QuoteProcessingException;
import com.insurance.platform.audit.service.AuditService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class UserEventListener {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UserEventListener.class);
    private final UserEventDecoder decoder;
    private final AuditService service;
    private final UserStatusEventDecoder statusDecoder;

    public UserEventListener(UserEventDecoder decoder, UserStatusEventDecoder statusDecoder, AuditService service) {
        this.decoder = decoder;
        this.statusDecoder = statusDecoder;
        this.service = service;
    }

    @KafkaListener(id = "user-audit", idIsGroup = false, topics = "${user.events.topic:insurance.user.events}")
    public void receive(ConsumerRecord<byte[], byte[]> record) {
        var decoded = decoder.decode(record.value());

        if (decoded.isEmpty()) {
            var status = statusDecoder.decode(record.value());
            if (status.isPresent()) {
                var event = status.get();
                try {
                    var result = service.recordUserStatus(event);
                    LOG.info("User status handled result={} eventId={} eventType={} userId={} correlationId={} partition={} offset={}",
                            result, event.eventId(), event.eventType(), event.aggregateId(), event.correlationId(), record.partition(), record.offset());
                } catch (RuntimeException failure) {
                    throw new QuoteProcessingException(failure);
                }
                return;
            }
            LOG.info("Ignored unsupported user event topic={} partition={} offset={}", record.topic(), record.partition(), record.offset());
            return;
        }

        try {
            var event = decoded.get();
            var result = service.registerUser(event);
            LOG.info("User event handled result={} eventId={} userId={} correlationId={} partition={} offset={}",
                    result, event.eventId(), event.aggregateId(), event.correlationId(), record.partition(), record.offset());
        } catch (RuntimeException failure) {
            throw new QuoteProcessingException(failure);
        }
    }
}
