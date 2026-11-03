package com.insurance.platform.notification.event;
import com.insurance.platform.notification.exception.QuoteProcessingException;
import com.insurance.platform.notification.service.NotificationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
@Component
public class UserEventListener {
    private static final Logger LOG = LoggerFactory.getLogger(UserEventListener.class);
    private final UserEventDecoder decoder;
    private final NotificationService service;

    public UserEventListener(UserEventDecoder decoder, NotificationService service) {
        this.decoder = decoder;
        this.service = service;
    }

    private EventObservation observation = new EventObservation(new SimpleMeterRegistry());
    @Autowired
    public void configureObservation(EventObservation observation) { this.observation = observation; }

    @KafkaListener(id = "user-notification", idIsGroup = false, topics = "${user.events.topic:insurance.user.events}")
    public void receive(ConsumerRecord<byte[], byte[]> record) {
        var decoded = decoder.decode(record.value());
        if (decoded.isEmpty()) {
            LOG.info("Ignored unsupported user event topic={} partition={} offset={}", record.topic(), record.partition(), record.offset());
            return;
        }
        try {
            var event = decoded.get();
            var result = observation.process(event.correlationId(), record.topic(), () -> service.registerUser(event));
            LOG.info("User event handled result={} eventId={} userId={} correlationId={} topic={} partition={} offset={}",
                    result, event.eventId(), event.aggregateId(), event.correlationId(), record.topic(), record.partition(), record.offset());
        } catch (RuntimeException failure) {
            throw new QuoteProcessingException(failure);
        }
    }
}
