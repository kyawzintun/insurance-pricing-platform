package com.insurance.platform.pricing.repository;

import com.insurance.platform.pricing.entity.PricingRule;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

// Calculation only: intentionally exposes no write operations.
public interface PricingRuleRepository extends Repository<PricingRule, UUID> {
    List<PricingRule> findByEnabledTrueAndEffectiveFromLessThanEqual(Instant now);
}
