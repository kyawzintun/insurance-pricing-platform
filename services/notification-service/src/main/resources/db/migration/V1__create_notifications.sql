CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    -- Cross-service references, not foreign keys.
    customer_id UUID NOT NULL,
    quote_id UUID,
    event_id UUID NOT NULL UNIQUE,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ
);
