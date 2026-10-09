package com.insurance.platform.quote.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicle_brands")
public class VehicleBrand {
    @Id
    private UUID id;
    private String name;
    private boolean active;
    private Instant createdAt;

    protected VehicleBrand() {}

    public VehicleBrand(UUID id, String name, boolean active, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.active = active;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
