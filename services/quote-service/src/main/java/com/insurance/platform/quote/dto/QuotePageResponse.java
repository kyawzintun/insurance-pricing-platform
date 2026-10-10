package com.insurance.platform.quote.dto;

import java.util.List;

public record QuotePageResponse(List<QuoteResponse> content, int page, int size, long totalElements, int totalPages) {
    public QuotePageResponse { content = List.copyOf(content); }
}
