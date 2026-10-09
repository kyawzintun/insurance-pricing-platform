package com.insurance.platform.quote.exception;

import com.insurance.platform.quote.dto.ApiError;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(QuoteException.class)
    ResponseEntity<ApiError> domain(QuoteException ex) {
        return ResponseEntity.status(ex.status()).body(new ApiError(ex.code(), ex.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ApiError> invalid(Exception ex) { return domain(QuoteException.invalid()); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(Exception ex) {
        return ResponseEntity.status(405).body(new ApiError("METHOD_NOT_ALLOWED", "Method not allowed"));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(Exception ex) {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Endpoint not found"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR", "Unable to create quote"));
    }
}
