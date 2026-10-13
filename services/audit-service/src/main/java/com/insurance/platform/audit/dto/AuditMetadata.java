package com.insurance.platform.audit.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Explicit allowlist for compact, non-sensitive JSONB context. */
public record AuditMetadata(String quoteReference, String status, String coverageType,
        BigDecimal finalPremium, String currency, Instant pricedAt, Instant expiresAt) {}
