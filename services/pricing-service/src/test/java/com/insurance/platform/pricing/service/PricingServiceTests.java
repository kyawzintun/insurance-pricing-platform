package com.insurance.platform.pricing.service;

import com.insurance.platform.pricing.dto.PricingRequest;
import com.insurance.platform.pricing.entity.PricingRule;
import com.insurance.platform.pricing.enums.*;
import com.insurance.platform.pricing.exception.*;
import com.insurance.platform.pricing.repository.PricingRuleRepository;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricingServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final PricingRuleRepository repository = mock(PricingRuleRepository.class);
    private final PricingService service = new PricingService(repository, Clock.fixed(NOW, ZoneOffset.UTC), VALIDATORS.getValidator());

    @AfterAll static void closeValidator() { VALIDATORS.close(); }

    private PricingRule rule(RuleType type, RuleOperator op, String value, String upper, String factor) {
        return new PricingRule(UUID.randomUUID(), type, op, value, upper, factor == null ? null : new BigDecimal(factor),
                type == RuleType.BASE_PREMIUM ? new BigDecimal("8000.00") : null, NOW, true, type.name());
    }
    private PricingRule base() { return rule(RuleType.BASE_PREMIUM, RuleOperator.EQUALS, null, null, null); }
    private void rules(PricingRule... rules) { when(repository.findByEnabledTrueAndEffectiveFromLessThanEqual(NOW)).thenReturn(List.of(rules)); }
    private PricingRequest request(int age, int experience, int vehicleAge, int claims, CoverageType coverage) {
        return new PricingRequest(LocalDate.of(2026, 10, 7).minusYears(age), experience, 2026 - vehicleAge,
                new BigDecimal("700000"), 1500, claims, coverage);
    }
    private PricingRequest standard() { return request(30, 8, 5, 1, CoverageType.COMPREHENSIVE); }

    @Test void baseOnly() {
        var base = base(); rules(base);
        var result = service.calculate(standard());
        assertThat(result.basePremium()).isEqualByComparingTo("8000.00");
        assertThat(result.finalPremium()).isEqualByComparingTo("8000.00");
        assertThat(result.basePricingRuleId()).isEqualTo(base.getId());
        assertThat(result.currency()).isEqualTo("THB");
        assertThat(result.calculatedAt()).isEqualTo(NOW);
        assertThat(result.adjustments()).isEmpty();
        verify(repository).findByEnabledTrueAndEffectiveFromLessThanEqual(NOW);
        verifyNoMoreInteractions(repository);
    }
    @ParameterizedTest @CsvSource({"24,10000", "25,8000"})
    void driverAgeBoundary(int age, String premium) {
        rules(base(),rule(RuleType.DRIVER_AGE, RuleOperator.LESS_THAN,"25",null,"1.25"));
        assertThat(service.calculate(request(age,8,5,1,CoverageType.COMPREHENSIVE)).finalPremium()).isEqualByComparingTo(premium);
    }
    @ParameterizedTest @CsvSource({"2,9200", "3,8000"})
    void experienceBoundary(int experience, String premium) {
        rules(base(),rule(RuleType.DRIVING_EXPERIENCE,RuleOperator.LESS_THAN,"3",null,"1.15"));
        assertThat(service.calculate(request(30,experience,5,1,CoverageType.COMPREHENSIVE)).finalPremium()).isEqualByComparingTo(premium);
    }
    @ParameterizedTest @CsvSource({"11,9600", "10,8000"})
    void vehicleAgeBoundary(int age, String premium) {
        rules(base(),rule(RuleType.VEHICLE_AGE,RuleOperator.GREATER_THAN,"10",null,"1.20"));
        assertThat(service.calculate(request(30,8,age,1,CoverageType.COMPREHENSIVE)).finalPremium()).isEqualByComparingTo(premium);
    }
    @ParameterizedTest @CsvSource({"2,10400", "1,8000"})
    void claimsBoundary(int claims, String premium) {
        rules(base(),rule(RuleType.PREVIOUS_CLAIMS,RuleOperator.GREATER_THAN_OR_EQUAL,"2",null,"1.30"));
        assertThat(service.calculate(request(30,8,5,claims,CoverageType.COMPREHENSIVE)).finalPremium()).isEqualByComparingTo(premium);
    }
    @ParameterizedTest @CsvSource({"COMPREHENSIVE,11200", "THIRD_PARTY,8000"})
    void coverage(CoverageType coverage, String premium) {
        rules(base(),rule(RuleType.COVERAGE_TYPE,RuleOperator.EQUALS,"COMPREHENSIVE",null,"1.40"),
                rule(RuleType.COVERAGE_TYPE,RuleOperator.EQUALS,"THIRD_PARTY",null,"1.00"));
        assertThat(service.calculate(request(30,8,5,1,coverage)).finalPremium()).isEqualByComparingTo(premium);
    }
    @ParameterizedTest @CsvSource({"EQUALS,700000,8800", "LESS_THAN_OR_EQUAL,700000,8800", "GREATER_THAN,700000,8000", "GREATER_THAN_OR_EQUAL,700000,8800", "LESS_THAN,700000,8000"})
    void vehicleValueOperators(RuleOperator operator, String operand, String premium) {
        rules(base(),rule(RuleType.VEHICLE_VALUE,operator,operand,null,"1.10"));
        assertThat(service.calculate(standard()).finalPremium()).isEqualByComparingTo(premium);
    }
    @ParameterizedTest @CsvSource({"20,8000", "21,8800", "25,8800", "30,8800", "31,8000"})
    void betweenIsInclusive(int age, String premium) {
        rules(base(),rule(RuleType.DRIVER_AGE,RuleOperator.BETWEEN,"21","30","1.10"));
        assertThat(service.calculate(request(age,8,5,1,CoverageType.COMPREHENSIVE)).finalPremium()).isEqualByComparingTo(premium);
    }
    @Test void multipleFactorsHaveStableOrderAndContinuousBreakdown() {
        var inputRules = new ArrayList<>(List.of(base(),
                rule(RuleType.COVERAGE_TYPE,RuleOperator.EQUALS,"COMPREHENSIVE",null,"1.40"),
                rule(RuleType.PREVIOUS_CLAIMS,RuleOperator.GREATER_THAN_OR_EQUAL,"2",null,"1.30"),
                rule(RuleType.VEHICLE_VALUE,RuleOperator.GREATER_THAN,"600000",null,"1.10"),
                rule(RuleType.VEHICLE_AGE,RuleOperator.GREATER_THAN,"10",null,"1.20"),
                rule(RuleType.DRIVING_EXPERIENCE,RuleOperator.LESS_THAN,"3",null,"1.15"),
                rule(RuleType.DRIVER_AGE,RuleOperator.LESS_THAN,"25",null,"1.25")));
        rules(inputRules.toArray(PricingRule[]::new));
        var response = service.calculate(request(24,2,11,2,CoverageType.COMPREHENSIVE));
        assertThat(response.finalPremium()).isEqualByComparingTo("27627.60");
        assertThat(response.adjustments()).extracting(a -> a.ruleType()).containsExactly(RuleType.DRIVER_AGE,
                RuleType.DRIVING_EXPERIENCE,RuleType.VEHICLE_AGE,RuleType.VEHICLE_VALUE,RuleType.PREVIOUS_CLAIMS,RuleType.COVERAGE_TYPE);
        var before=response.basePremium(); int sequence=1;
        for(var adjustment:response.adjustments()) {
            assertThat(adjustment.amountBefore()).isEqualTo(before);
            assertThat(adjustment.sequenceNumber()).isEqualTo(sequence++);
            assertThat(adjustment.pricingRuleId()).isNotNull();
            before=adjustment.amountAfter();
        }
        Collections.reverse(inputRules); rules(inputRules.toArray(PricingRule[]::new));
        assertThat(service.calculate(request(24,2,11,2,CoverageType.COMPREHENSIVE))).isEqualTo(response);
    }
    @Test void disabledAndFutureRulesAreIgnoredEvenIfInvalid() {
        var disabled = new PricingRule(UUID.randomUUID(),RuleType.DRIVER_AGE,RuleOperator.LESS_THAN,"invalid",null,null,null,NOW,false,"disabled");
        var future = new PricingRule(UUID.randomUUID(),RuleType.DRIVER_AGE,RuleOperator.LESS_THAN,"invalid",null,null,null,NOW.plusSeconds(1),true,"future");
        rules(base(),disabled,future);
        assertThat(service.calculate(standard()).finalPremium()).isEqualByComparingTo("8000");
    }
    @Test void newestMatchingRuleWinsWithStableIdTieBreak() {
        var older = new PricingRule(UUID.randomUUID(),RuleType.DRIVER_AGE,RuleOperator.LESS_THAN,"40",null,new BigDecimal("2"),null,NOW.minusSeconds(1),true,"older");
        var winner = new PricingRule(new UUID(0,1),RuleType.DRIVER_AGE,RuleOperator.LESS_THAN,"40",null,new BigDecimal("1.1"),null,NOW,true,"winner");
        var loser = new PricingRule(new UUID(0,2),RuleType.DRIVER_AGE,RuleOperator.LESS_THAN,"40",null,new BigDecimal("3"),null,NOW,true,"loser");
        rules(base(),loser,older,winner);
        assertThat(service.calculate(standard()).adjustments()).singleElement().satisfies(a -> assertThat(a.pricingRuleId()).isEqualTo(winner.getId()));
    }
    @Test void missingBaseFails() { rules(); assertThatThrownBy(() -> service.calculate(standard())).isInstanceOf(PricingConfigurationException.class); }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"invalid","1e999999999","NaN","","1.12345"})
    void invalidComparisonFailsEvenWhenRuleWouldNotApply(String operand) {
        rules(base(),rule(RuleType.DRIVER_AGE,RuleOperator.LESS_THAN,operand,null,"1.25"));
        assertThatThrownBy(() -> service.calculate(standard())).isInstanceOf(PricingConfigurationException.class).hasMessage("Pricing configuration is unavailable or invalid");
    }
    @Test void reversedBetweenFails() {
        rules(base(),rule(RuleType.DRIVER_AGE,RuleOperator.BETWEEN,"30","20","1.25"));
        assertThatThrownBy(() -> service.calculate(standard())).isInstanceOf(PricingConfigurationException.class);
    }
    @Test void invalidCoverageOperatorFails() {
        rules(base(),rule(RuleType.COVERAGE_TYPE,RuleOperator.GREATER_THAN,"COMPREHENSIVE",null,"1.4"));
        assertThatThrownBy(() -> service.calculate(standard())).isInstanceOf(PricingConfigurationException.class);
    }
    @Test void halfUpRoundingHappensAtEveryStep() {
        var smallBase=new PricingRule(UUID.randomUUID(),RuleType.BASE_PREMIUM,RuleOperator.EQUALS,null,null,null,new BigDecimal("0.05"),NOW,true,"rounding");
        rules(smallBase,rule(RuleType.DRIVER_AGE,RuleOperator.LESS_THAN,"40",null,"1.10"),
                rule(RuleType.COVERAGE_TYPE,RuleOperator.EQUALS,"COMPREHENSIVE",null,"1.10"));
        var result=service.calculate(standard());
        assertThat(result.adjustments().getFirst().amountAfter()).isEqualTo(new BigDecimal("0.06"));
        assertThat(result.finalPremium()).isEqualTo(new BigDecimal("0.07"));
    }
    @Test void dateValidationUsesInjectedClock() {
        var invalid=new PricingRequest(LocalDate.of(2026,10,7),8,2021,new BigDecimal("700000"),1500,1,CoverageType.COMPREHENSIVE);
        assertThatThrownBy(() -> service.calculate(invalid)).isInstanceOf(InvalidPricingRequestException.class);
        verifyNoInteractions(repository);
    }
    @Test void birthdayHasNotYetOccurred() {
        rules(base(),rule(RuleType.DRIVER_AGE,RuleOperator.LESS_THAN,"25",null,"1.25"));
        var input=new PricingRequest(LocalDate.of(2001,10,8),8,2021,new BigDecimal("700000"),1500,1,CoverageType.COMPREHENSIVE);
        assertThat(service.calculate(input).adjustments().getFirst().inputValue()).isEqualTo("24");
    }
    @Test void missingFactorFails() {
        rules(base(),rule(RuleType.DRIVER_AGE,RuleOperator.LESS_THAN,"25",null,null));
        assertThatThrownBy(() -> service.calculate(standard())).isInstanceOf(PricingConfigurationException.class);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"0", "-1"})
    void nonpositiveBaseFails(String amount) {
        rules(new PricingRule(UUID.randomUUID(),RuleType.BASE_PREMIUM,RuleOperator.EQUALS,null,null,null,
                new BigDecimal(amount),NOW,true,"invalid base"));
        assertThatThrownBy(() -> service.calculate(standard())).isInstanceOf(PricingConfigurationException.class);
    }
    @Test void premiumOverflowFailsSafely() {
        rules(new PricingRule(UUID.randomUUID(),RuleType.BASE_PREMIUM,RuleOperator.EQUALS,null,null,null,
                new BigDecimal("9999999999999.99"),NOW,true,"large base"),
                rule(RuleType.COVERAGE_TYPE,RuleOperator.EQUALS,"COMPREHENSIVE",null,"1.4"));
        assertThatThrownBy(() -> service.calculate(standard())).isInstanceOf(PricingConfigurationException.class);
    }
}
