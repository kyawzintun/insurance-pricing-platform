package com.insurance.platform.auth.exception;

import com.insurance.platform.auth.dto.ApiError;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AuthException.class)
    ResponseEntity<ApiError> authError(AuthException ex) {
        return ResponseEntity.status(ex.status()).body(new ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ApiError> invalidRequest(Exception ex) {
        // Never include rejected values: they may contain passwords.
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Invalid request"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        // Do not serialize or log request data or potentially sensitive exception messages.
        return ResponseEntity.internalServerError()
                .body(new ApiError("INTERNAL_ERROR", "Unable to process request"));
    }
}
