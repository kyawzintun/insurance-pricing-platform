package com.insurance.platform.pricing.controller;

import com.insurance.platform.pricing.dto.PricingRequest;
import com.insurance.platform.pricing.dto.PricingResponse;
import com.insurance.platform.pricing.service.PricingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/pricing")
public class PricingController {
    private final PricingService pricingService;

    public PricingController(PricingService pricingService) { this.pricingService = pricingService; }

    @PostMapping("/calculate")
    public PricingResponse calculate(@Valid @RequestBody PricingRequest request) {
        return pricingService.calculate(request);
    }
}
