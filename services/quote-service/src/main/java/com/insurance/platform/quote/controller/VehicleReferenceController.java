package com.insurance.platform.quote.controller;

import com.insurance.platform.quote.dto.VehicleReferenceResponse;
import com.insurance.platform.quote.exception.QuoteException;
import com.insurance.platform.quote.service.VehicleReferenceService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicles/brands")
public class VehicleReferenceController {
    private final VehicleReferenceService service;
    public VehicleReferenceController(VehicleReferenceService service) { this.service = service; }

    @GetMapping
    public List<VehicleReferenceResponse> brands() { return service.brands(); }

    @GetMapping("/{brandId}/models")
    public List<VehicleReferenceResponse> models(@PathVariable String brandId) {
        UUID id;
        try {
            id = UUID.fromString(brandId);
            if (!id.toString().equalsIgnoreCase(brandId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new QuoteException(HttpStatus.BAD_REQUEST, "INVALID_VEHICLE_SELECTION", "Invalid vehicle selection");
        }
        return service.models(id);
    }
}
