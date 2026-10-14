package com.insurance.platform.audit.repository;

import com.insurance.platform.audit.entity.AuditRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, UUID> {
    boolean existsByEventId(UUID eventId);
}
