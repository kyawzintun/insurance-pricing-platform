package com.insurance.platform.notification.event;
import com.insurance.platform.notification.service.NotificationService;
import com.insurance.platform.notification.exception.QuoteProcessingException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
@Component
public class UserEventListener {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UserEventListener.class);
    private final UserEventDecoder decoder;
    private final NotificationService service;

    public UserEventListener(UserEventDecoder decoder, NotificationService service) {
        this.decoder = decoder;
        this.service = service;
    }

    @KafkaListener(id = "user-notification", idIsGroup = false, topics = "${user.events.topic:insurance.user.events}")
    public void receive(ConsumerRecord<byte[], byte[]> record) {
        var decoded = decoder.decode(record.value());
        if (decoded.isEmpty()) {
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
