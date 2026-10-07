package com.insurance.platform.pricing.exception;

public class PricingConfigurationException extends RuntimeException {
    public PricingConfigurationException() { super("Pricing configuration is unavailable or invalid"); }
}
