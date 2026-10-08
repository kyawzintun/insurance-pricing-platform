package com.insurance.platform.pricing.exception;

import org.springframework.http.HttpStatus;

public class PricingRuleException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private PricingRuleException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }

    public String code() { return code; }

    public static PricingRuleException invalid() {
        return new PricingRuleException(HttpStatus.BAD_REQUEST, "INVALID_PRICING_RULE", "Invalid pricing rule");
    }

    public static PricingRuleException notFound() {
        return new PricingRuleException(HttpStatus.NOT_FOUND, "PRICING_RULE_NOT_FOUND", "Pricing rule not found");
    }

    public static PricingRuleException conflict() {
        return new PricingRuleException(HttpStatus.CONFLICT, "PRICING_RULE_VERSION_CONFLICT",
                "Pricing rule was modified by another request");
    }
}
