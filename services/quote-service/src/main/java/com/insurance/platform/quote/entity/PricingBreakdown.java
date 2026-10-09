package com.insurance.platform.quote.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

@Entity
@Table(name = "pricing_breakdowns")
public class PricingBreakdown {
    @Id
    private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false, unique = true)
    private Quote quote;
    @Column(precision = 15, scale = 2)
    private BigDecimal basePremium;
    @Column(precision = 15, scale = 2)
    private BigDecimal finalPremium;
    private String currency;
    private Instant calculatedAt;
    @OneToMany(mappedBy = "pricingBreakdown", cascade = CascadeType.ALL)
    @OrderBy("sequenceNumber ASC")
    private List<PricingAdjustment> adjustments = new ArrayList<>();

    protected PricingBreakdown() {}

    public PricingBreakdown(
            UUID id, Quote quote, BigDecimal basePremium,
            BigDecimal finalPremium, String currency, Instant calculatedAt) {
        this.id = id;
        this.quote = quote;
        this.basePremium = basePremium;
        this.finalPremium = finalPremium;
        this.currency = currency;
        this.calculatedAt = calculatedAt;
    }

    public UUID getId() { return id; }
    public Quote getQuote() { return quote; }
    public BigDecimal getBasePremium() { return basePremium; }
    public BigDecimal getFinalPremium() { return finalPremium; }
    public String getCurrency() { return currency; }
    public Instant getCalculatedAt() { return calculatedAt; }
    public void addAdjustment(PricingAdjustment adjustment) { adjustments.add(adjustment); }
    public List<PricingAdjustment> getAdjustments() { return List.copyOf(adjustments); }
}
