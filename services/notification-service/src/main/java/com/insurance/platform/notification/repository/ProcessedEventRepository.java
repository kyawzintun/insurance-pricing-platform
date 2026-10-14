package com.insurance.platform.notification.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProcessedEventRepository {
    private final EntityManager entityManager;

    public ProcessedEventRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    // Join the business transaction; never commit a marker independently.
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryMarkProcessed(UUID eventId, String consumerName, Instant processedAt) {
        return entityManager.createNativeQuery("""
                INSERT INTO processed_events (event_id, consumer_name, processed_at)
                VALUES (:eventId, :consumerName, :processedAt)
                ON CONFLICT (event_id, consumer_name) DO NOTHING
                """)
                .setParameter("eventId", eventId)
                .setParameter("consumerName", consumerName)
                .setParameter("processedAt", processedAt)
                .executeUpdate() == 1;
    }
}
