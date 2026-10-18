package com.insurance.platform.audit.exception;
public class InvalidUserEventException extends RuntimeException {
    public InvalidUserEventException(String reason) { super(reason); }
}
