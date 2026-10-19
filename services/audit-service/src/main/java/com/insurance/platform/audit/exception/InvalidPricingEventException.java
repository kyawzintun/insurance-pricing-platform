package com.insurance.platform.audit.exception;
/** Fixed safe reasons only; never retain payload-bearing causes. */
public class InvalidPricingEventException extends RuntimeException {
    public InvalidPricingEventException(String reason) { super(reason); }
}
