package com.insurance.platform.pricing.exception;

import com.insurance.platform.pricing.dto.ApiError;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({InvalidPricingRequestException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, ConstraintViolationException.class,
            HttpMediaTypeNotSupportedException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> invalidRequest(Exception ex, HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/v1/admin/pricing")) {
            return ruleError(PricingRuleException.invalid());
        }
        return ResponseEntity.badRequest().body(new ApiError("INVALID_PRICING_REQUEST", "Invalid pricing request"));
    }

    @ExceptionHandler(PricingRuleException.class)
    ResponseEntity<ApiError> ruleError(PricingRuleException ex) {
        return ResponseEntity.status(ex.status()).body(new ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    ResponseEntity<ApiError> versionConflict(Exception ex) {
        return ruleError(PricingRuleException.conflict());
    }

    @ExceptionHandler({PricingConfigurationException.class, DataAccessException.class})
    ResponseEntity<ApiError> invalidConfiguration(Exception ex) {
        return ResponseEntity.internalServerError().body(new ApiError(
                "PRICING_CONFIGURATION_ERROR", "Pricing configuration is unavailable or invalid"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR", "Unable to process request"));
    }
}
