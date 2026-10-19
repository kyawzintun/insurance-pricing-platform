package com.insurance.platform.pricing.service;

import com.insurance.platform.pricing.dto.*;
import com.insurance.platform.pricing.entity.PricingRule;
import com.insurance.platform.pricing.enums.RuleType;
import com.insurance.platform.pricing.exception.PricingConfigurationException;
import com.insurance.platform.pricing.exception.PricingRuleException;
import com.insurance.platform.pricing.repository.PricingRuleRepository;
import com.insurance.platform.pricing.repository.OutboxRepository;
import com.insurance.platform.pricing.event.PricingAdminIdentity;
import com.insurance.platform.pricing.event.PricingCorrelationId;
import tools.jackson.databind.json.JsonMapper;
import com.insurance.platform.pricing.event.PricingRuleEventData;
import com.insurance.platform.pricing.event.EventEnvelope;
import com.insurance.platform.pricing.entity.OutboxEvent;
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
    private final OutboxRepository outbox;
    private final PricingAdminIdentity identity;
    private final PricingCorrelationId correlation;
    private final JsonMapper mapper;

    public PricingRuleAdminService(
            PricingRuleRepository repository,
            Clock clock,
            Validator validator,
            OutboxRepository outbox,
            PricingAdminIdentity identity,
            PricingCorrelationId correlation,
            JsonMapper mapper) {
        this.outbox = outbox;
        this.identity = identity;
        this.correlation = correlation;
        this.mapper = mapper;
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
        var actor = identity.current();
        var rule = new PricingRule(UUID.randomUUID(), request.ruleType(), request.operator(),
                request.comparisonValue(), request.comparisonValueTo(), request.factor(), request.fixedAmount(),
                request.effectiveFrom().truncatedTo(ChronoUnit.MICROS), request.enabled(), request.description());
        validateRule(rule);
        var changedAt = now();
        rule.initializeTimestamps(changedAt);
        var saved = repository.saveAndFlush(rule);
        recordEvent(saved, "PricingRuleCreated", actor, changedAt);
        return PricingRuleResponse.from(saved);
    }

    @Transactional
    public PricingRuleResponse update(UUID id, UpdatePricingRuleRequest request) {
        validateRequest(request);
        var actor = identity.current();
        var existing = find(id);
        checkVersion(existing, request.version());
        var values = new PricingRule(id, request.ruleType(), request.operator(), request.comparisonValue(),
                request.comparisonValueTo(), request.factor(), request.fixedAmount(), request.effectiveFrom().truncatedTo(ChronoUnit.MICROS),
                request.enabled(), request.description());
        validateRule(values);
        var changedAt = now();
        existing.updateFrom(values, changedAt);
        // Flush before mapping the response: Hibernate increments @Version during the SQL UPDATE.
        repository.flush();
        recordEvent(existing, "PricingRuleUpdated", actor, changedAt);
        return PricingRuleResponse.from(existing);
    }

    @Transactional
    public PricingRuleResponse setEnabled(UUID id, PricingRuleVersionRequest request, boolean enabled) {
        validateRequest(request);
        var actor = identity.current();
        var rule = find(id);
        checkVersion(rule, request.version());
        validateRule(rule);
        var changedAt = now();
        rule.changeEnabled(enabled, changedAt);
        repository.flush();
        recordEvent(rule, enabled ? "PricingRuleEnabled" : "PricingRuleDisabled", actor, changedAt);
        return PricingRuleResponse.from(rule);
    }

    private void recordEvent(PricingRule rule, String type, UUID actor, Instant now) {
        var data = PricingRuleEventData.from(rule, actor, now);
        var event = new EventEnvelope<>(UUID.randomUUID(), type, 1,
                "PricingRule", rule.getId(), now, correlation.current(), "pricing-service", data);
        outbox.saveAndFlush(new OutboxEvent(event, mapper.writeValueAsString(event)));
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
