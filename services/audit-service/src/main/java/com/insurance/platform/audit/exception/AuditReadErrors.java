package com.insurance.platform.audit.exception;

import com.insurance.platform.audit.controller.AuditReadController;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice(assignableTypes = AuditReadController.class)
public class AuditReadErrors {
    @ExceptionHandler({ResponseStatusException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid(Exception ex) {
        int status = ex instanceof ResponseStatusException r ? r.getStatusCode().value() : 400;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", status == 404 ? "NOT_FOUND" : "BAD_REQUEST",
                "message", status == 404 ? "Audit record not found" : "Invalid audit query"));
    }
}
