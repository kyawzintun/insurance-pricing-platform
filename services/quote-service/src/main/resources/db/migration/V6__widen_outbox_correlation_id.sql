-- Preserve the existing QuotePriced/Gateway correlation ID contract (up to 128 characters).
ALTER TABLE outbox_events ALTER COLUMN correlation_id TYPE VARCHAR(128);
