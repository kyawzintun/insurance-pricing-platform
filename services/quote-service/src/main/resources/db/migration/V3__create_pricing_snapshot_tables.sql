CREATE TABLE pricing_breakdowns (
    id UUID PRIMARY KEY,
    quote_id UUID NOT NULL UNIQUE REFERENCES quotes (id),
    base_premium NUMERIC(15,2) NOT NULL CHECK (base_premium >= 0),
    final_premium NUMERIC(15,2) NOT NULL CHECK (final_premium >= 0),
    currency VARCHAR(3) NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE pricing_adjustments (
    id UUID PRIMARY KEY,
    pricing_breakdown_id UUID NOT NULL REFERENCES pricing_breakdowns (id),
    -- Pricing-owned identity; deliberately not a cross-service foreign key.
    pricing_rule_id UUID,
    rule_type VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    input_value VARCHAR(255),
    factor NUMERIC(10,4) NOT NULL CHECK (factor >= 0),
    amount_before NUMERIC(15,2) NOT NULL CHECK (amount_before >= 0),
    amount_after NUMERIC(15,2) NOT NULL CHECK (amount_after >= 0),
    sequence_number INTEGER NOT NULL CHECK (sequence_number >= 0)
);

CREATE INDEX idx_pricing_adjustments_breakdown ON pricing_adjustments (pricing_breakdown_id);
