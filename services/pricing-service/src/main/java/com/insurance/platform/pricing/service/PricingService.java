package com.insurance.platform.pricing.service;

import com.insurance.platform.pricing.dto.PricingAdjustment;
import com.insurance.platform.pricing.dto.PricingRequest;
import com.insurance.platform.pricing.dto.PricingResponse;
import com.insurance.platform.pricing.entity.PricingRule;
import com.insurance.platform.pricing.enums.CoverageType;
import com.insurance.platform.pricing.enums.RuleOperator;
import com.insurance.platform.pricing.enums.RuleType;
import com.insurance.platform.pricing.exception.InvalidPricingRequestException;
import com.insurance.platform.pricing.exception.PricingConfigurationException;
import com.insurance.platform.pricing.repository.PricingRuleRepository;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingService {
    private static final List<RuleType> ADJUSTMENT_ORDER = List.of(RuleType.DRIVER_AGE,
            RuleType.DRIVING_EXPERIENCE, RuleType.VEHICLE_AGE, RuleType.VEHICLE_VALUE,
            RuleType.PREVIOUS_CLAIMS, RuleType.COVERAGE_TYPE);
    // Newest effective matching rule wins; UUID text ascending resolves equal timestamps.
    private static final Comparator<PricingRule> RULE_ORDER = Comparator
            .comparing(PricingRule::getEffectiveFrom).reversed()
            .thenComparing(rule -> rule.getId().toString());
    private static final BigDecimal MAX_PREMIUM = new BigDecimal("9999999999999.99");
    private final PricingRuleRepository repository;
    private final Clock clock;
    private final Validator validator;

    public PricingService(PricingRuleRepository repository, Clock clock, Validator validator) {
        this.repository = repository;
        this.clock = clock;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public PricingResponse calculate(PricingRequest request) {
        var now = clock.instant();
        var today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new InvalidPricingRequestException();
        }
        if (!request.dateOfBirth().isBefore(today) || request.vehicleManufacturingYear() > today.getYear()) {
            throw new InvalidPricingRequestException();
        }
        int driverAge = Period.between(request.dateOfBirth(), today).getYears();
        int vehicleAge = today.getYear() - request.vehicleManufacturingYear();
        // Repository filters in SQL; retain the invariant here for isolated engine tests too.
        var rules = repository.findByEnabledTrueAndEffectiveFromLessThanEqual(now).stream()
                .filter(rule -> rule.isEnabled() && !rule.getEffectiveFrom().isAfter(now))
                .toList();
        // Validate every active rule, including nonmatching rules, so broken configuration is visible.
        rules.forEach(this::validateRule);
        var ordered = rules.stream().sorted(RULE_ORDER).toList();
        var base = ordered.stream().filter(rule -> rule.getRuleType() == RuleType.BASE_PREMIUM)
                .findFirst().orElseThrow(PricingConfigurationException::new);
        var basePremium = money(base.getFixedAmount());
        var premium = basePremium;
        var adjustments = new ArrayList<PricingAdjustment>();
        for (var type : ADJUSTMENT_ORDER) {
            String input = inputValue(type, request, driverAge, vehicleAge);
            var matching = ordered.stream().filter(rule -> rule.getRuleType() == type)
                    .filter(rule -> matches(rule, input)).findFirst();
            if (matching.isPresent()) {
                var rule = matching.get();
                var before = premium;
                premium = money(before.multiply(rule.getFactor()));
                adjustments.add(new PricingAdjustment(rule.getId(), rule.getVersion(), type,
                        rule.getDescription(), input, rule.getFactor(), before, premium, adjustments.size() + 1));
            }
        }
        return new PricingResponse(base.getId(), base.getVersion(), basePremium, premium, "THB", now, adjustments);
    }

    private String inputValue(RuleType type, PricingRequest request, int driverAge, int vehicleAge) {
        return switch (type) {
            case DRIVER_AGE -> Integer.toString(driverAge);
            case DRIVING_EXPERIENCE -> request.drivingExperienceYears().toString();
            case VEHICLE_AGE -> Integer.toString(vehicleAge);
            case VEHICLE_VALUE -> request.vehicleValue().toPlainString();
            case PREVIOUS_CLAIMS -> request.previousClaimsCount().toString();
            case COVERAGE_TYPE -> request.coverageType().name();
            case BASE_PREMIUM -> throw new PricingConfigurationException();
        };
    }

    private void validateRule(PricingRule rule) {
        if (rule.getId() == null || rule.getRuleType() == null || rule.getOperator() == null) {
            throw new PricingConfigurationException();
        }
        if (rule.getRuleType() == RuleType.BASE_PREMIUM) {
            if (rule.getOperator() != RuleOperator.EQUALS || rule.getFixedAmount() == null
                    || rule.getFixedAmount().signum() <= 0 || rule.getFactor() != null
                    || rule.getComparisonValue() != null || rule.getComparisonValueTo() != null) {
                throw new PricingConfigurationException();
            }
            money(rule.getFixedAmount());
            return;
        }
        if (rule.getFactor() == null || rule.getFactor().signum() < 0 || rule.getFixedAmount() != null) {
            throw new PricingConfigurationException();
        }
        if (rule.getRuleType() == RuleType.COVERAGE_TYPE) {
            if (rule.getOperator() != RuleOperator.EQUALS || rule.getComparisonValueTo() != null) {
                throw new PricingConfigurationException();
            }
            try {
                CoverageType.valueOf(rule.getComparisonValue());
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new PricingConfigurationException();
            }
        } else {
            var lower = numeric(rule.getComparisonValue());
            if (rule.getOperator() == RuleOperator.BETWEEN) {
                if (lower.compareTo(numeric(rule.getComparisonValueTo())) > 0) {
                    throw new PricingConfigurationException();
                }
            } else if (rule.getComparisonValueTo() != null) {
                throw new PricingConfigurationException();
            }
        }
    }

    private boolean matches(PricingRule rule, String input) {
        if (rule.getRuleType() == RuleType.COVERAGE_TYPE) {
            return input.equals(rule.getComparisonValue());
        }
        var value = numeric(input);
        int comparison = value.compareTo(numeric(rule.getComparisonValue()));
        return switch (rule.getOperator()) {
            case EQUALS -> comparison == 0;
            case LESS_THAN -> comparison < 0;
            case LESS_THAN_OR_EQUAL -> comparison <= 0;
            case GREATER_THAN -> comparison > 0;
            case GREATER_THAN_OR_EQUAL -> comparison >= 0;
            case BETWEEN -> comparison >= 0 && value.compareTo(numeric(rule.getComparisonValueTo())) <= 0;
        };
    }

    private BigDecimal numeric(String value) {
        // Plain decimals only; bound parsing and reject scientific notation/extreme exponents.
        if (value == null || !value.matches("[+-]?[0-9]{1,15}(\\.[0-9]{1,4})?")) {
            throw new PricingConfigurationException();
        }
        return new BigDecimal(value);
    }

    private BigDecimal money(BigDecimal amount) {
        var result = amount.setScale(2, RoundingMode.HALF_UP);
        if (result.signum() < 0 || result.compareTo(MAX_PREMIUM) > 0) {
            throw new PricingConfigurationException();
        }
        return result;
    }
}
