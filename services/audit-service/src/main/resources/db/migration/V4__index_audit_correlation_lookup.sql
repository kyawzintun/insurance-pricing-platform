-- Exact correlation search otherwise scans the complete append-heavy audit table.
CREATE INDEX idx_audit_records_correlation_id ON audit_records (correlation_id);
