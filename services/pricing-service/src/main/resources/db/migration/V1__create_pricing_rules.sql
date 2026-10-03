CREATE TABLE pricing_rules (
    id UUID PRIMARY KEY,
    rule_type VARCHAR(50) NOT NULL,
    operator VARCHAR(50) NOT NULL,
    comparison_value VARCHAR(255),
    comparison_value_to VARCHAR(255),
    factor NUMERIC(10,4) CHECK (factor >= 0),
    fixed_amount NUMERIC(15,2) CHECK (fixed_amount >= 0),
    effective_from TIMESTAMPTZ NOT NULL,
    enabled BOOLEAN NOT NULL,
    description VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_pricing_rules_active_lookup ON pricing_rules (rule_type, enabled, effective_from);
