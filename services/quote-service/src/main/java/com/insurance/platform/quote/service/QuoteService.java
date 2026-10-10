package com.insurance.platform.quote.service;

import com.insurance.platform.quote.client.PricingClient;
import com.insurance.platform.quote.dto.*;
import com.insurance.platform.quote.exception.QuoteException;
import com.insurance.platform.quote.repository.*;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Orchestration deliberately has no database transaction across the remote HTTP call. */
@Service
public class QuoteService {
    private final VehicleBrandRepository brands;
    private final VehicleModelRepository models;
    private final PricingClient pricing;
    private final QuotePersistenceService persistence;
    private final Clock clock;
    private final Validator validator;

    public QuoteService(VehicleBrandRepository brands, VehicleModelRepository models, PricingClient pricing,
                        QuotePersistenceService persistence, Clock clock, Validator validator) {
        this.brands = brands;
        this.models = models;
        this.pricing = pricing;
        this.persistence = persistence;
        this.clock = clock;
        this.validator = validator;
    }

    public QuoteResponse create(UUID customerId, CreateQuoteRequest request) {
        var today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (customerId == null || request == null || !validator.validate(request).isEmpty()) throw QuoteException.invalid();
        if (!request.dateOfBirth().isBefore(today) || request.vehicleManufacturingYear() > today.getYear()) throw QuoteException.invalid();
        var brand = brands.findById(request.vehicleBrandId()).orElseThrow(() -> new QuoteException(
                HttpStatus.NOT_FOUND, "VEHICLE_BRAND_NOT_FOUND", "Vehicle brand not found"));
        var model = models.findById(request.vehicleModelId()).orElseThrow(() -> new QuoteException(
                HttpStatus.NOT_FOUND, "VEHICLE_MODEL_NOT_FOUND", "Vehicle model not found"));
        if (!brand.isActive() || !model.isActive() || !model.getBrandId().equals(brand.getId())) {
            throw new QuoteException(HttpStatus.BAD_REQUEST, "INVALID_VEHICLE_SELECTION", "Invalid vehicle selection");
        }
        var vehicle = new VehicleSnapshot(brand.getId(), brand.getName(), model.getId(), model.getName(),
                request.vehicleManufacturingYear(), request.vehicleValue(), request.engineSizeCc());
        var result = pricing.calculate(request);
        return persistence.create(customerId, request, vehicle, result);
    }
}
