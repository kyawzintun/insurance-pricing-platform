package com.insurance.platform.audit.exception;

/** Retryable processing failure with a safe class name, never a payload-bearing cause/message. */
public class QuoteProcessingException extends RuntimeException {
    private final String failureClass;
    public QuoteProcessingException(RuntimeException failure) {
        super("PERSISTENCE_FAILURE");
        this.failureClass = failure.getClass().getName();
    }
    public String failureClass() { return failureClass; }
}
