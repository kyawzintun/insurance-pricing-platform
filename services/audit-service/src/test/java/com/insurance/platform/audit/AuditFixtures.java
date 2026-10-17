package com.insurance.platform.audit;

import java.time.Instant;
import java.util.UUID;

final class AuditFixtures {
    static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID QUOTE = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID CUSTOMER = UUID.fromString("00000000-0000-4000-8000-000000000003");
    static String json() { return """
        {"eventId":"00000000-0000-4000-8000-000000000001","eventType":"QuotePriced",
         "eventVersion":1,"aggregateType":"Quote","aggregateId":"00000000-0000-4000-8000-000000000002",
         "occurredAt":"2026-10-12T09:00:00Z","correlationId":"phase14-test","producer":"quote-service",
         "data":{"quoteId":"00000000-0000-4000-8000-000000000002","quoteReference":"Q-TEST-123",
           "customerId":"00000000-0000-4000-8000-000000000003","status":"PRICED",
           "coverageType":"COMPREHENSIVE","finalPremium":11200.00,"currency":"THB",
           "pricedAt":"2026-10-12T09:00:00Z","expiresAt":"2026-11-11T09:00:00Z"}}
        """; }
    static String expiredJson() {
        return json().replace("QuotePriced", "QuoteExpired").replace("PRICED", "EXPIRED")
                .replace("pricedAt", "originalExpiresAt").replace("expiresAt", "expiredAt")
                .replace("2026-11-11T09:00:00Z", "2026-10-12T09:00:00Z");
    }
}
