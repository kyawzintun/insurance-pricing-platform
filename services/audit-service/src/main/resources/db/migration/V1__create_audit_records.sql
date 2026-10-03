CREATE TABLE audit_records (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    actor_type VARCHAR(50),
    actor_id VARCHAR(100),
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(100),
    occurred_at TIMESTAMPTZ NOT NULL,
    producer VARCHAR(100),
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_records_entity ON audit_records (entity_type, entity_id);
CREATE INDEX idx_audit_records_occurred_at ON audit_records (occurred_at DESC);
CREATE INDEX idx_audit_records_event_occurred_at ON audit_records (event_type, occurred_at DESC);
-- The composite event_type index also supports lookups by event_type alone.
