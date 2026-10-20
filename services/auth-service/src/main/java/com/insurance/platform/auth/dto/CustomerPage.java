package com.insurance.platform.auth.dto;

import java.util.List;

public record CustomerPage(
        List<CustomerResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
