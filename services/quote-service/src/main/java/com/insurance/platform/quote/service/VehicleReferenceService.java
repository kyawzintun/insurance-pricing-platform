package com.insurance.platform.quote.service;

import com.insurance.platform.quote.dto.VehicleReferenceResponse;
import com.insurance.platform.quote.exception.QuoteException;
import com.insurance.platform.quote.repository.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class VehicleReferenceService {
    private final VehicleBrandRepository brands;
    private final VehicleModelRepository models;

    public VehicleReferenceService(VehicleBrandRepository brands, VehicleModelRepository models) {
        this.brands = brands;
        this.models = models;
    }

    public List<VehicleReferenceResponse> brands() {
        return brands.findByActiveTrueOrderByNameAscIdAsc().stream()
                .map(b -> new VehicleReferenceResponse(b.getId(), b.getName())).toList();
    }

    public List<VehicleReferenceResponse> models(UUID brandId) {
        brands.findById(brandId).filter(b -> b.isActive()).orElseThrow(() -> new QuoteException(
                HttpStatus.NOT_FOUND, "VEHICLE_BRAND_NOT_FOUND", "Vehicle brand not found"));
        return models.findByBrandIdAndActiveTrueOrderByNameAscIdAsc(brandId).stream()
                .map(m -> new VehicleReferenceResponse(m.getId(), m.getName())).toList();
    }
}
