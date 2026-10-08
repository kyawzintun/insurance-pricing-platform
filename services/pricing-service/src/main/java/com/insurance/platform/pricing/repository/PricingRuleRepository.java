package com.insurance.platform.pricing.repository;

import com.insurance.platform.pricing.entity.PricingRule;
import com.insurance.platform.pricing.enums.RuleType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// Only the operations used by calculation and administration; no deletion API.
public interface PricingRuleRepository extends Repository<PricingRule, UUID> {
    List<PricingRule> findByEnabledTrueAndEffectiveFromLessThanEqual(Instant now);
    Optional<PricingRule> findById(UUID id);

    PricingRule saveAndFlush(PricingRule rule);

    void flush();

    @Query("select r from PricingRule r where (:ruleType is null or r.ruleType = :ruleType) "
            + "and (:enabled is null or r.enabled = :enabled)")
    Page<PricingRule> findRules(@Param("ruleType") RuleType ruleType,
                               @Param("enabled") Boolean enabled, Pageable pageable);
}
