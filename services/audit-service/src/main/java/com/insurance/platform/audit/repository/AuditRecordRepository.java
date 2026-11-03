package com.insurance.platform.audit.repository;

import com.insurance.platform.audit.entity.AuditRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, UUID>, JpaSpecificationExecutor<AuditRecord> {
    boolean existsByEventId(UUID eventId);
}
