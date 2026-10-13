package com.insurance.platform.notification.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {
    @Id private UUID id;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "quote_id")
    private UUID quoteId;
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;
    @Column(nullable = false, length = 50)
    private String type;
    @Column(nullable = false, length = 30)
    private String status;
    @Column(nullable = false, columnDefinition = "text")
    private String message;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "processed_at")
    private Instant processedAt;

    protected Notification() {}

    public Notification(UUID id, UUID customerId, UUID quoteId, UUID eventId, String message, Instant now) {
        this.id = id;
        this.customerId = customerId;
        this.quoteId = quoteId;
        this.eventId = eventId;
        this.type = "QUOTE_PRICED";
        this.status = "CREATED";
        this.message = message;
        this.createdAt = now;
        this.processedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public UUID getQuoteId() { return quoteId; }
    public UUID getEventId() { return eventId; }
    public String getType() { return type; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
}
