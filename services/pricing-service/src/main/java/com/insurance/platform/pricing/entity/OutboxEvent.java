package com.insurance.platform.pricing.entity;

import com.insurance.platform.pricing.event.EventEnvelope;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable event intent; only the poller updates operational columns through SQL. */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id private UUID id;

    @Column(updatable = false)
    private UUID eventId;

    @Column(updatable = false)
    private String aggregateType;

    @Column(updatable = false)
    private String aggregateId;

    @Column(updatable = false)
    private String eventType;

    @Column(updatable = false)
    private int eventVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private String payload;

    @Column(updatable = false)
    private String correlationId;

    private String status;

    private int retryCount;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant publishedAt;

    protected OutboxEvent() {}

    public OutboxEvent(EventEnvelope<?> event, String payload) {
        this.id = UUID.randomUUID();
        this.eventId = event.eventId();
        this.aggregateType = event.aggregateType();
        this.aggregateId = event.aggregateId().toString();
        this.eventType = event.eventType();
        this.eventVersion = event.eventVersion();
        this.payload = payload;
        this.correlationId = event.correlationId();
        this.status = "PENDING";
        this.retryCount = 0;
        this.createdAt = event.occurredAt();
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getPayload() { return payload; }
}
