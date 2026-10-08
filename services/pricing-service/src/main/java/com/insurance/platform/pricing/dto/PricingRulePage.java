package com.insurance.platform.pricing.dto;

import java.util.List;

public record PricingRulePage(List<PricingRuleResponse> content, int page, int size,
                              long totalElements, int totalPages) {
    public PricingRulePage { content = List.copyOf(content); }
}
