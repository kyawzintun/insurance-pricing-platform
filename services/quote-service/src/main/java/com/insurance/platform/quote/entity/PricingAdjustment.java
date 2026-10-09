package com.insurance.platform.quote.entity;

import com.insurance.platform.quote.enums.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "pricing_adjustments")
public class PricingAdjustment {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pricing_breakdown_id", nullable = false)
    private PricingBreakdown pricingBreakdown;
    private UUID pricingRuleId;
    @Enumerated(EnumType.STRING)
    private PricingRuleType ruleType;
    private String description;
    private String inputValue;
    @Column(precision = 10, scale = 4)
    private BigDecimal factor;
    @Column(precision = 15, scale = 2)
    private BigDecimal amountBefore;
    @Column(precision = 15, scale = 2)
    private BigDecimal amountAfter;
    private int sequenceNumber;

    protected PricingAdjustment() {}

    public PricingAdjustment(
            UUID id, PricingBreakdown pricingBreakdown, UUID pricingRuleId,
            PricingRuleType ruleType, String description, String inputValue,
            BigDecimal factor, BigDecimal amountBefore, BigDecimal amountAfter,
            int sequenceNumber) {
        this.id = id;
        this.pricingBreakdown = pricingBreakdown;
        this.pricingRuleId = pricingRuleId;
        this.ruleType = ruleType;
        this.description = description;
        this.inputValue = inputValue;
        this.factor = factor;
        this.amountBefore = amountBefore;
        this.amountAfter = amountAfter;
        this.sequenceNumber = sequenceNumber;
    }

    public UUID getId() { return id; }
    public PricingBreakdown getPricingBreakdown() { return pricingBreakdown; }
    public UUID getPricingRuleId() { return pricingRuleId; }
    public PricingRuleType getRuleType() { return ruleType; }
    public String getDescription() { return description; }
    public String getInputValue() { return inputValue; }
    public BigDecimal getFactor() { return factor; }
    public BigDecimal getAmountBefore() { return amountBefore; }
    public BigDecimal getAmountAfter() { return amountAfter; }
    public int getSequenceNumber() { return sequenceNumber; }
}
