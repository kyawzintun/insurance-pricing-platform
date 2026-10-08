package com.insurance.platform.pricing.service;

import com.insurance.platform.pricing.dto.*;
import com.insurance.platform.pricing.entity.PricingRule;
import com.insurance.platform.pricing.enums.RuleType;
import com.insurance.platform.pricing.exception.PricingConfigurationException;
import com.insurance.platform.pricing.exception.PricingRuleException;
import com.insurance.platform.pricing.repository.PricingRuleRepository;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PricingRuleAdminService {
    private final PricingRuleRepository repository;
    private final Clock clock;
    private final Validator validator;

    public PricingRuleAdminService(PricingRuleRepository repository, Clock clock, Validator validator) {
        this.repository = repository;
        this.clock = clock;
        this.validator = validator;
    }

    public PricingRulePage list(int page, int size, RuleType ruleType, Boolean enabled) {
        if (page < 0 || size < 1 || size > 100) throw PricingRuleException.invalid();
        var results = repository.findRules(ruleType, enabled, PageRequest.of(page, size, Sort.by("id")));
        return new PricingRulePage(results.getContent().stream().map(PricingRuleResponse::from).toList(),
                page, size, results.getTotalElements(), results.getTotalPages());
    }

    public PricingRuleResponse get(UUID id) { return PricingRuleResponse.from(find(id)); }

    @Transactional
    public PricingRuleResponse create(CreatePricingRuleRequest request) {
        validateRequest(request);
        var rule = new PricingRule(UUID.randomUUID(), request.ruleType(), request.operator(),
                request.comparisonValue(), request.comparisonValueTo(), request.factor(), request.fixedAmount(),
                request.effectiveFrom().truncatedTo(ChronoUnit.MICROS), request.enabled(), request.description());
        validateRule(rule);
        rule.initializeTimestamps(now());
        return PricingRuleResponse.from(repository.saveAndFlush(rule));
    }

    @Transactional
    public PricingRuleResponse update(UUID id, UpdatePricingRuleRequest request) {
        validateRequest(request);
        var existing = find(id);
        checkVersion(existing, request.version());
        var values = new PricingRule(id, request.ruleType(), request.operator(), request.comparisonValue(),
                request.comparisonValueTo(), request.factor(), request.fixedAmount(), request.effectiveFrom().truncatedTo(ChronoUnit.MICROS),
                request.enabled(), request.description());
        validateRule(values);
        existing.updateFrom(values, now());
        // Flush before mapping the response: Hibernate increments @Version during the SQL UPDATE.
        repository.flush();
        return PricingRuleResponse.from(existing);
    }

    @Transactional
    public PricingRuleResponse setEnabled(UUID id, PricingRuleVersionRequest request, boolean enabled) {
        validateRequest(request);
        var rule = find(id);
        checkVersion(rule, request.version());
        validateRule(rule);
        rule.changeEnabled(enabled, now());
        repository.flush();
        return PricingRuleResponse.from(rule);
    }

    private PricingRule find(UUID id) { return repository.findById(id).orElseThrow(PricingRuleException::notFound); }

    private void checkVersion(PricingRule rule, long expected) {
        if (rule.getVersion() != expected) throw PricingRuleException.conflict();
    }

    private void validateRequest(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) throw PricingRuleException.invalid();
    }

    private void validateRule(PricingRule rule) {
        // Keep dates within the ordinary ISO four-digit year range; future dates are allowed.
        if (rule.getEffectiveFrom() == null || rule.getEffectiveFrom().isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || rule.getEffectiveFrom().isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            throw PricingRuleException.invalid();
        }
        try { PricingRuleValidator.validateRule(rule); }
        catch (PricingConfigurationException ex) { throw PricingRuleException.invalid(); }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
