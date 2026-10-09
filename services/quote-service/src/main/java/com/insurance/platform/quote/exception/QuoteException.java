package com.insurance.platform.quote.exception;

import org.springframework.http.HttpStatus;

public class QuoteException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public QuoteException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static QuoteException invalid() {
        return new QuoteException(HttpStatus.BAD_REQUEST, "INVALID_QUOTE_REQUEST", "Invalid quote request");
    }
    public static QuoteException pricingError() {
        return new QuoteException(HttpStatus.BAD_GATEWAY, "PRICING_SERVICE_ERROR", "Unable to obtain a valid price");
    }
    public static QuoteException pricingUnavailable() {
        return new QuoteException(HttpStatus.SERVICE_UNAVAILABLE, "PRICING_SERVICE_UNAVAILABLE", "Pricing service is unavailable");
    }
}
