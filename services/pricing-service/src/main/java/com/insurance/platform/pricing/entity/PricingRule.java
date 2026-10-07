package com.insurance.platform.pricing.entity;

import com.insurance.platform.pricing.enums.RuleType;
import com.insurance.platform.pricing.enums.RuleOperator;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pricing_rules")
public class PricingRule {
    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    private RuleType ruleType;
    @Enumerated(EnumType.STRING)
    private RuleOperator operator;
    private String comparisonValue;
    private String comparisonValueTo;
    @Column(precision = 10, scale = 4)
    private BigDecimal factor;
    @Column(precision = 15, scale = 2)
    private BigDecimal fixedAmount;
    private Instant effectiveFrom;
    private boolean enabled;
    private String description;
    @Version
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected PricingRule() {}

    public PricingRule(UUID id, RuleType ruleType, RuleOperator operator, String comparisonValue,
            String comparisonValueTo, BigDecimal factor, BigDecimal fixedAmount, Instant effectiveFrom,
            boolean enabled, String description) {
        this.id = id;
        this.ruleType = ruleType;
        this.operator = operator;
        this.comparisonValue = comparisonValue;
        this.comparisonValueTo = comparisonValueTo;
        this.factor = factor;
        this.fixedAmount = fixedAmount;
        this.effectiveFrom = effectiveFrom;
        this.enabled = enabled;
        this.description = description;
    }

    public UUID getId() { return id; }
    public RuleType getRuleType() { return ruleType; }
    public RuleOperator getOperator() { return operator; }
    public String getComparisonValue() { return comparisonValue; }
    public String getComparisonValueTo() { return comparisonValueTo; }
    public BigDecimal getFactor() { return factor; }
    public BigDecimal getFixedAmount() { return fixedAmount; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public boolean isEnabled() { return enabled; }
    public String getDescription() { return description; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
