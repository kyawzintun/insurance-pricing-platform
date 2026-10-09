package com.insurance.platform.quote.service;

import com.insurance.platform.quote.client.PricingResult;
import com.insurance.platform.quote.dto.*;
import com.insurance.platform.quote.entity.*;
import com.insurance.platform.quote.enums.QuoteStatus;
import com.insurance.platform.quote.repository.QuoteRepository;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Separate Spring bean so the transaction starts only after successful pricing. */
@Service
public class QuotePersistenceService {
    private final QuoteRepository repository;
    private final Clock clock;
    public QuotePersistenceService(QuoteRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }

    @Transactional
    public QuoteResponse create(UUID customerId, CreateQuoteRequest request, VehicleSnapshot vehicle, PricingResult pricing) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var id = UUID.randomUUID();
        var reference = "Q-" + DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.ofInstant(now, ZoneOffset.UTC))
                + "-" + id.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        var quote = new Quote(id, reference, customerId, QuoteStatus.PRICED, request.coverageType(),
                pricing.finalPremium(), pricing.currency(), now, now, now.plus(30, ChronoUnit.DAYS), now);
        var driver = new QuoteDriver(UUID.randomUUID(), quote, request.dateOfBirth(), request.drivingExperienceYears(), request.previousClaimsCount());
        var storedVehicle = new QuoteVehicle(UUID.randomUUID(), quote, vehicle.brandId(), vehicle.brandName(), vehicle.modelId(),
                vehicle.modelName(), vehicle.manufacturingYear(), vehicle.vehicleValue(), vehicle.engineSizeCc());
        var breakdown = new PricingBreakdown(UUID.randomUUID(), quote, pricing.basePremium(), pricing.finalPremium(),
                pricing.currency(), pricing.calculatedAt().truncatedTo(ChronoUnit.MICROS));
        for (var adjustment : pricing.adjustments()) {
            breakdown.addAdjustment(new PricingAdjustment(UUID.randomUUID(), breakdown, adjustment.pricingRuleId(),
                    adjustment.ruleType(), adjustment.description(), adjustment.inputValue(), adjustment.factor(),
                    adjustment.amountBefore(), adjustment.amountAfter(), adjustment.sequenceNumber()));
        }
        quote.attachSnapshots(driver, storedVehicle, breakdown);
        return QuoteResponse.from(repository.saveAndFlush(quote));
    }
}
