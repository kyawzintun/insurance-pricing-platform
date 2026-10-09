package com.insurance.platform.quote.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "quote_vehicles")
public class QuoteVehicle {
    @Id
    private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false, unique = true)
    private Quote quote;
    private UUID brandId;
    private String brandName;
    private UUID modelId;
    private String modelName;
    private int manufacturingYear;
    @Column(precision = 15, scale = 2)
    private BigDecimal vehicleValue;
    private int engineSizeCc;

    protected QuoteVehicle() {}

    public QuoteVehicle(
            UUID id, Quote quote, UUID brandId,
            String brandName, UUID modelId, String modelName,
            int manufacturingYear, BigDecimal vehicleValue, int engineSizeCc) {
        this.id = id;
        this.quote = quote;
        this.brandId = brandId;
        this.brandName = brandName;
        this.modelId = modelId;
        this.modelName = modelName;
        this.manufacturingYear = manufacturingYear;
        this.vehicleValue = vehicleValue;
        this.engineSizeCc = engineSizeCc;
    }

    public UUID getId() { return id; }
    public Quote getQuote() { return quote; }
    public UUID getBrandId() { return brandId; }
    public String getBrandName() { return brandName; }
    public UUID getModelId() { return modelId; }
    public String getModelName() { return modelName; }
    public int getManufacturingYear() { return manufacturingYear; }
    public BigDecimal getVehicleValue() { return vehicleValue; }
    public int getEngineSizeCc() { return engineSizeCc; }
}
