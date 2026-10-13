package com.insurance.platform.audit.exception;

/** Only fixed safe reason codes; never attach a payload-bearing exception. */
public class InvalidQuoteEventException extends RuntimeException {
    public InvalidQuoteEventException(String reason) { super(reason); }
}
