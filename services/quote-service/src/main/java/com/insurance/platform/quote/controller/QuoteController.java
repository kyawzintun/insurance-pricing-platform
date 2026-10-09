package com.insurance.platform.quote.controller;

import com.insurance.platform.quote.dto.CreateQuoteRequest;
import com.insurance.platform.quote.dto.QuoteResponse;
import com.insurance.platform.quote.service.QuoteService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quotes")
public class QuoteController {
    private final QuoteService service;
    public QuoteController(QuoteService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateQuoteRequest request) {
        return service.create(UUID.fromString(jwt.getSubject()), request);
    }
}
