package com.insurance.platform.quote.repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** One session lock per quote database, held across sends, with no transaction open during Kafka I/O. */
@Repository
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true")
public class OutboxPollingRepository {
    // Stable namespace and key shared by every Quote instance. Never change during a rolling deployment.
    private static final String LOCK = "741016, 1";
    private final DataSource dataSource;

    public OutboxPollingRepository(DataSource dataSource) { this.dataSource = dataSource; }

    public Optional<Batch> claim(int limit) throws SQLException {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Outbox batch size must be 1..500");
        }

        Connection connection = dataSource.getConnection();
        boolean locked = false;
        try {
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT pg_try_advisory_lock(" + LOCK + ")")) {
                result.next();
                locked = result.getBoolean(1);
            }

            if (!locked) {
                connection.close();
                return Optional.empty();
            }

            var batch = new Batch(connection);
            try {
                batch.load(limit);
                return Optional.of(batch);
            } catch (SQLException | RuntimeException failure) {
                batch.close();
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            if (!locked) connection.close();
            throw failure;
        }
    }

    public record PendingEvent(UUID id,
                               UUID eventId,
                               String aggregateId,
                               String eventType,
                               String payload,
                               String correlationId,
                               int retryCount) {}

    public static class Batch implements AutoCloseable {
        private final Connection connection;
        private List<PendingEvent> events = List.of();
        private Batch(Connection connection) { this.connection = connection; }
        private void load(int limit) throws SQLException {
            var rows = new ArrayList<PendingEvent>();
            try (var statement = connection.prepareStatement("""
                    SELECT id, event_id, aggregate_id, event_type, payload::text, correlation_id, retry_count
                    FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at, id LIMIT ?
                    """)) {
                statement.setInt(1, limit);
                try (var result = statement.executeQuery()) {
                    while (result.next()) rows.add(new PendingEvent(result.getObject(1, UUID.class),
                            result.getObject(2, UUID.class), result.getString(3), result.getString(4),
                            result.getString(5), result.getString(6), result.getInt(7)));
                }
            }
            events = List.copyOf(rows);
        }

        public List<PendingEvent> events() { return events; }
        
        public void published(UUID id, Instant now) throws SQLException {
            try (var statement = connection.prepareStatement("""
                    UPDATE outbox_events SET status = 'PUBLISHED', published_at = ?
                    WHERE id = ? AND status = 'PENDING'
                    """)) {
                statement.setTimestamp(1, Timestamp.from(now)); statement.setObject(2, id);
                if (statement.executeUpdate() != 1) throw new SQLException("Outbox row no longer pending");
            }
        }

        public void failed(UUID id) throws SQLException {
            try (var statement = connection.prepareStatement("""
                    UPDATE outbox_events SET retry_count = retry_count + 1
                    WHERE id = ? AND status = 'PENDING'
                    """)) {
                statement.setObject(1, id);
                if (statement.executeUpdate() != 1) throw new SQLException("Outbox row no longer pending");
            }
        }

        @Override
        public void close() throws SQLException {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT pg_advisory_unlock(" + LOCK + ")")) {
                result.next();
                if (!result.getBoolean(1)) {
                    throw new SQLException("Outbox lock was not held");
                }
            } catch (SQLException failure) {
                // Do not return a possibly locked PostgreSQL session to the connection pool.
                connection.abort(Runnable::run);
                throw failure;
            } finally { connection.close(); }
        }
    }
}
