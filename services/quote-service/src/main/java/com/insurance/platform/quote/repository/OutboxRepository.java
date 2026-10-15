package com.insurance.platform.quote.repository;

import com.insurance.platform.quote.entity.OutboxEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {}
