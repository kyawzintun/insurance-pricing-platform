package com.insurance.platform.quote.entity;

import com.insurance.platform.quote.enums.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quotes")
public class Quote {
    @Id
    private UUID id;
    private String quoteReference;
    private UUID customerId;
    @Enumerated(EnumType.STRING)
    private QuoteStatus status;
    @Enumerated(EnumType.STRING)
    private CoverageType coverageType;
    @Column(precision = 15, scale = 2)
    private BigDecimal premiumAmount;
    private String currency;
    private Instant createdAt;
    private Instant pricedAt;
    private Instant expiresAt;
    private Instant updatedAt;
    @OneToOne(mappedBy = "quote", cascade = CascadeType.ALL)
    private QuoteDriver driver;
    @OneToOne(mappedBy = "quote", cascade = CascadeType.ALL)
    private QuoteVehicle vehicle;
    @OneToOne(mappedBy = "quote", cascade = CascadeType.ALL)
    private PricingBreakdown pricing;

    protected Quote() {}

    public Quote(
            UUID id, String quoteReference, UUID customerId,
            QuoteStatus status, CoverageType coverageType, BigDecimal premiumAmount,
            String currency, Instant createdAt, Instant pricedAt,
            Instant expiresAt, Instant updatedAt) {
        this.id = id;
        this.quoteReference = quoteReference;
        this.customerId = customerId;
        this.status = status;
        this.coverageType = coverageType;
        this.premiumAmount = premiumAmount;
        this.currency = currency;
        this.createdAt = createdAt;
        this.pricedAt = pricedAt;
        this.expiresAt = expiresAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public String getQuoteReference() { return quoteReference; }
    public UUID getCustomerId() { return customerId; }
    public QuoteStatus getStatus() { return status; }
    public CoverageType getCoverageType() { return coverageType; }
    public BigDecimal getPremiumAmount() { return premiumAmount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPricedAt() { return pricedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void attachSnapshots(QuoteDriver driver, QuoteVehicle vehicle, PricingBreakdown pricing) {
        this.driver = driver;
        this.vehicle = vehicle;
        this.pricing = pricing;
    }
    public QuoteDriver getDriver() { return driver; }
    public QuoteVehicle getVehicle() { return vehicle; }
    public PricingBreakdown getPricing() { return pricing; }
}
