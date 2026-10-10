package com.insurance.platform.quote.service;

import com.insurance.platform.quote.dto.QuotePageResponse;
import com.insurance.platform.quote.dto.QuoteResponse;
import com.insurance.platform.quote.entity.Quote;
import com.insurance.platform.quote.exception.QuoteException;
import com.insurance.platform.quote.repository.QuoteRepository;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stored snapshots only: no Pricing client or current catalog dependency. */
@Service
@Transactional(readOnly = true)
public class QuoteRetrievalService {
    private final QuoteRepository repository;

    public QuoteRetrievalService(QuoteRepository repository) { this.repository = repository; }

    public QuoteResponse get(UUID id, UUID customerId, boolean admin) {
        var quote = (admin ? repository.findById(id) : repository.findByIdAndCustomerId(id, customerId))
                .orElseThrow(() -> new QuoteException(HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND", "Quote not found"));
        return QuoteResponse.from(quote);
    }

    public QuotePageResponse list(UUID customerId, boolean admin, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw invalidPage();
        }
        var pageable = PageRequest.of(page, size);
        var ids = admin ? repository.findIds(pageable) : repository.findIdsByCustomerId(customerId, pageable);
        if (ids.isEmpty()) return new QuotePageResponse(List.of(), page, size, ids.getTotalElements(), ids.getTotalPages());
        var snapshots = admin ? repository.findSnapshotsByIds(ids.getContent())
                : repository.findSnapshotsByIdsAndCustomerId(ids.getContent(), customerId);
        var byId = snapshots.stream().collect(Collectors.toMap(Quote::getId, Function.identity()));
        // IN queries do not preserve order. Restore the deterministic ID-page order explicitly.
        var content = ids.getContent().stream().map(byId::get).map(QuoteResponse::from).toList();
        return new QuotePageResponse(content, page, size, ids.getTotalElements(), ids.getTotalPages());
    }

    public static QuoteException invalidPage() {
        return new QuoteException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST", "Invalid page request");
    }
}
