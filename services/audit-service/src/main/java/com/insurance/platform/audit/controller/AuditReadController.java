package com.insurance.platform.audit.controller;

import com.insurance.platform.audit.dto.AuditRow;
import com.insurance.platform.audit.entity.AuditRecord;
import com.insurance.platform.audit.repository.AuditRecordRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit")
@Transactional(readOnly = true)
public class AuditReadController {
    private final AuditRecordRepository repository;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final Map<String, Integer> FIELDS = Map.of("eventType", 100, "actorType", 50, "actorId", 100,
            "entityType", 50, "entityId", 100, "correlationId", 128, "producer", 100, "eventId", 36);

    public AuditReadController(AuditRecordRepository repository) {
        this.repository = repository;
    }

    public record AuditPage(List<AuditRow> content, int page, int size, long totalElements, int totalPages) {
    }

    public record AuditDetail(UUID id,
                              UUID eventId,
                              String eventType,
                              String actorType,
                              String actorId,
                              String entityType,
                              String entityId,
                              String correlationId,
                              String producer,
                              Instant occurredAt,
                              Instant createdAt,
                              JsonNode metadata) {
    }

    @GetMapping
    public ResponseEntity<AuditPage> list(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam Map<String, String> params) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw badRequest();
        }

        Instant from = timestamp(params.get("from")), to = timestamp(params.get("to"));

        if (from != null && to != null && from.isAfter(to)) {
            throw badRequest();
        }

        Specification<AuditRecord> spec = (r, q, b) -> b.conjunction();
        for (var field : FIELDS.entrySet()) {
            String value = params.get(field.getKey());
            if (value == null || value.isBlank()) continue;
            if (value.length() > field.getValue()) throw badRequest();
            Object match = value;
            if (Set.of("actorId", "entityId", "eventId").contains(field.getKey())) {
                UUID uuid = uuid(value);
                match = field.getKey().equals("eventId") ? uuid : uuid.toString();
            }
            Object exact = match;
            spec = spec.and((r, q, b) -> b.equal(r.get(field.getKey()), exact));
        }

        if (from != null) {
            spec = spec.and((r, q, b) -> b.greaterThanOrEqualTo(r.get("occurredAt"), from));
        }

        if (to != null) {
            spec = spec.and((r, q, b) -> b.lessThan(r.get("occurredAt"), to));
        }
        
        var result = repository.findAll(spec, PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AuditPage(
                result.getContent().stream().map(AuditRow::from).toList(), page, size, result.getTotalElements(), result.getTotalPages()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AuditDetail> detail(@PathVariable String id) {
        var r = repository.findById(uuid(id)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit record not found"));
        JsonNode metadata = r.getMetadata() == null ? null : json.readTree(r.getMetadata());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AuditDetail(r.getId(), r.getEventId(),
                r.getEventType(), r.getActorType(), r.getActorId(), r.getEntityType(), r.getEntityId(), r.getCorrelationId(),
                r.getProducer(), r.getOccurredAt(), r.getCreatedAt(), metadata));
    }

    private static UUID uuid(String value) {
        try {
            var id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw badRequest();
            return id;
        } catch (IllegalArgumentException ex) {
            throw badRequest();
        }
    }

    private static Instant timestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw badRequest();
        }
    }

    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid audit query");
    }
}
