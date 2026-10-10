package com.insurance.platform.quote.controller;

import com.insurance.platform.quote.dto.CreateQuoteRequest;
import com.insurance.platform.quote.dto.QuoteResponse;
import com.insurance.platform.quote.dto.QuotePageResponse;
import com.insurance.platform.quote.service.QuoteRetrievalService;
import com.insurance.platform.quote.exception.QuoteException;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import java.util.Set;
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
    private final QuoteRetrievalService retrieval;
    public QuoteController(QuoteService service, QuoteRetrievalService retrieval) {
        this.service = service;
        this.retrieval = retrieval;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateQuoteRequest request) {
        return service.create(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping("/{id}")
    public QuoteResponse get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        UUID quoteId;
        try {
            quoteId = UUID.fromString(id);
            if (!quoteId.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new QuoteException(HttpStatus.BAD_REQUEST, "INVALID_QUOTE_REQUEST", "Invalid quote ID");
        }
        return retrieval.get(quoteId, UUID.fromString(jwt.getSubject()), isAdmin(authentication));
    }

    @GetMapping
    public QuotePageResponse list(@RequestParam MultiValueMap<String, String> parameters,
                                  @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        // No customerId, status, or client-controlled sort filter in this phase, for either role.
        if (!Set.of("page", "size").containsAll(parameters.keySet())
                || parameters.values().stream().anyMatch(values -> values.size() != 1)) {
            throw QuoteRetrievalService.invalidPage();
        }
        return retrieval.list(UUID.fromString(jwt.getSubject()), isAdmin(authentication),
                integerParameter(parameters, "page", 0), integerParameter(parameters, "size", 20));
    }

    private static int integerParameter(MultiValueMap<String, String> parameters, String name, int defaultValue) {
        if (!parameters.containsKey(name)) return defaultValue;
        try { return Integer.parseInt(parameters.getFirst(name)); }
        catch (NumberFormatException ex) { throw QuoteRetrievalService.invalidPage(); }
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }
}
