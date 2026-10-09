package com.insurance.platform.quote.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicle_models")
public class VehicleModel {
    @Id
    private UUID id;
    private UUID brandId;
    private String name;
    private boolean active;
    private Instant createdAt;

    protected VehicleModel() {}

    public VehicleModel(UUID id, UUID brandId, String name, boolean active, Instant createdAt) {
        this.id = id;
        this.brandId = brandId;
        this.name = name;
        this.active = active;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getBrandId() { return brandId; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
