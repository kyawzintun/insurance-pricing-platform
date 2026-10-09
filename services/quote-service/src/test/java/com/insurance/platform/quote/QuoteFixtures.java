package com.insurance.platform.quote;

import com.insurance.platform.quote.client.PricingResult;
import com.insurance.platform.quote.dto.CreateQuoteRequest;
import com.insurance.platform.quote.enums.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

final class QuoteFixtures {
    static final UUID BRAND = UUID.fromString("f0a67386-c7ae-562e-ae46-9fe3042cf38e");
    static final UUID MODEL = UUID.fromString("7966e042-1127-5b17-8e0c-a6799b4e67d0");
    static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    static final String INPUT = """
            {"dateOfBirth":"1995-04-20","drivingExperienceYears":8,"previousClaimsCount":1,
             "vehicleBrandId":"f0a67386-c7ae-562e-ae46-9fe3042cf38e","vehicleModelId":"7966e042-1127-5b17-8e0c-a6799b4e67d0",
             "vehicleManufacturingYear":2021,"vehicleValue":700000.00,"engineSizeCc":1500,"coverageType":"COMPREHENSIVE"}
            """;
    static final String PRICE = """
            {"basePricingRuleId":"75ddf7df-77ce-57bc-8604-f2c0b95b76c5","basePricingRuleVersion":0,
             "basePremium":8000.00,"finalPremium":11200.00,"currency":"THB","calculatedAt":"2026-10-08T10:00:00Z",
             "adjustments":[{"pricingRuleId":"e8b09cf1-c3f3-57e3-a1fb-1dc907680679","pricingRuleVersion":0,
              "ruleType":"COVERAGE_TYPE","description":"Comprehensive coverage","inputValue":"COMPREHENSIVE",
              "factor":1.4000,"amountBefore":8000.00,"amountAfter":11200.00,"sequenceNumber":1}]}
            """;
    static CreateQuoteRequest request() {
        return new CreateQuoteRequest(LocalDate.of(1995,4,20),8,1,BRAND,MODEL,2021,new BigDecimal("700000.00"),1500,CoverageType.COMPREHENSIVE);
    }
    static PricingResult price() {
        return new PricingResult(UUID.fromString("75ddf7df-77ce-57bc-8604-f2c0b95b76c5"),0L,
                new BigDecimal("8000.00"),new BigDecimal("11200.00"),"THB",NOW,
                List.of(new PricingResult.Adjustment(UUID.fromString("e8b09cf1-c3f3-57e3-a1fb-1dc907680679"),0L,
                        PricingRuleType.COVERAGE_TYPE,"Comprehensive coverage","COMPREHENSIVE",new BigDecimal("1.4000"),
                        new BigDecimal("8000.00"),new BigDecimal("11200.00"),1)));
    }
}
