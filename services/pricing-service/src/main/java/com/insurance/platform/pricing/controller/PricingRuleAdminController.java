package com.insurance.platform.pricing.controller;

import com.insurance.platform.pricing.dto.*;
import com.insurance.platform.pricing.enums.RuleType;
import com.insurance.platform.pricing.service.PricingRuleAdminService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/pricing/rules")
public class PricingRuleAdminController {
    private final PricingRuleAdminService service;
    public PricingRuleAdminController(PricingRuleAdminService service) { this.service = service; }

    @GetMapping
    public PricingRulePage list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) RuleType ruleType,
            @RequestParam(required = false) Boolean enabled) {
        return service.list(page, size, ruleType, enabled);
    }

    @GetMapping("/{id}")
    public PricingRuleResponse get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<PricingRuleResponse> create(@Valid @RequestBody CreatePricingRuleRequest request) {
        var result = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/pricing/rules/" + result.id())).body(result);
    }

    @PutMapping("/{id}")
    public PricingRuleResponse update(@PathVariable UUID id, @Valid @RequestBody UpdatePricingRuleRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/enable")
    public PricingRuleResponse enable(@PathVariable UUID id, @Valid @RequestBody PricingRuleVersionRequest request) {
        return service.setEnabled(id, request, true);
    }

    @PatchMapping("/{id}/disable")
    public PricingRuleResponse disable(@PathVariable UUID id, @Valid @RequestBody PricingRuleVersionRequest request) {
        return service.setEnabled(id, request, false);
    }
}
