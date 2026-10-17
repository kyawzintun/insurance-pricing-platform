package com.insurance.platform.audit.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_records")
public class AuditRecord {
    @Id private UUID id;
    @Column(name = "event_id", nullable = false, unique = true) private UUID eventId;
    @Column(name = "event_type", nullable = false, length = 100) private String eventType;
    @Column(name = "actor_type", length = 50) private String actorType;
    @Column(name = "actor_id", length = 100) private String actorId;
    @Column(name = "entity_type", nullable = false, length = 50) private String entityType;
    @Column(name = "entity_id", nullable = false, length = 100) private String entityId;
    @Column(name = "correlation_id", length = 128) private String correlationId;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(length = 100) private String producer;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private String metadata;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected AuditRecord() {}
    public AuditRecord(UUID id, UUID eventId, String actorId, String entityId, String correlationId,
                       Instant occurredAt, String producer, String metadata, Instant createdAt) {
        this.id = id; this.eventId = eventId; this.eventType = "QuotePriced";
        this.actorType = "CUSTOMER"; this.actorId = actorId;
        this.entityType = "Quote"; this.entityId = entityId; this.correlationId = correlationId;
        this.occurredAt = occurredAt; this.producer = producer; this.metadata = metadata; this.createdAt = createdAt;
    }
    public AuditRecord(UUID id, UUID eventId, String actorId, String entityId, String correlationId,
            Instant occurredAt, String producer, String metadata, Instant createdAt, String eventType, String actorType) {
        this(id, eventId, actorId, entityId, correlationId, occurredAt, producer, metadata, createdAt);
        this.eventType = eventType; this.actorType = actorType;
    }
    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getActorType() { return actorType; }
    public String getActorId() { return actorId; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getCorrelationId() { return correlationId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getProducer() { return producer; }
    public String getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
}
