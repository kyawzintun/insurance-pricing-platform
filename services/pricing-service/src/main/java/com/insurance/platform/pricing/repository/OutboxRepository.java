package com.insurance.platform.pricing.repository;

import com.insurance.platform.pricing.entity.OutboxEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {}
