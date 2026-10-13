-- Phase 12/Gateway permit correlation IDs up to 128 characters.
-- Preserve the complete existing wire value; no producer contract change or truncation.
ALTER TABLE audit_records ALTER COLUMN correlation_id TYPE VARCHAR(128);
