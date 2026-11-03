package com.insurance.platform.gateway.operations;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Only allowlisted values cross the Gateway boundary; no downstream bodies or URLs. */
public record OperationsOverview(String overallStatus, Instant checkedAt, List<Service> services) {
    public record Service(String id, String name, String status, String readiness,
                          long responseTimeMs, Instant checkedAt, String errorCode, Map<String, Object> metrics) {}
}
