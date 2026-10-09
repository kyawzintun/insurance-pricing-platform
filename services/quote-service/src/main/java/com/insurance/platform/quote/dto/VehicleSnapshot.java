package com.insurance.platform.quote.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record VehicleSnapshot(UUID brandId, String brandName, UUID modelId, String modelName,
        int manufacturingYear, BigDecimal vehicleValue, int engineSizeCc) {}
