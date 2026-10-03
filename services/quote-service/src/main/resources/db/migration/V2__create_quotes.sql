CREATE TABLE quotes (
    id UUID PRIMARY KEY,
    quote_reference VARCHAR(50) NOT NULL UNIQUE,
    -- Auth-owned identity; deliberately not a cross-service foreign key.
    customer_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    coverage_type VARCHAR(30) NOT NULL,
    premium_amount NUMERIC(15,2) CHECK (premium_amount >= 0),
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    priced_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_quotes_customer_created_at ON quotes (customer_id, created_at DESC);
CREATE INDEX idx_quotes_status_expires_at ON quotes (status, expires_at);

CREATE TABLE quote_drivers (
    id UUID PRIMARY KEY,
    quote_id UUID NOT NULL UNIQUE REFERENCES quotes (id),
    date_of_birth DATE NOT NULL,
    driving_experience_years INTEGER NOT NULL CHECK (driving_experience_years >= 0),
    previous_claims_count INTEGER NOT NULL CHECK (previous_claims_count >= 0)
);

CREATE TABLE quote_vehicles (
    id UUID PRIMARY KEY,
    quote_id UUID NOT NULL UNIQUE REFERENCES quotes (id),
    -- Snapshot/reference IDs are retained without catalog foreign keys.
    brand_id UUID NOT NULL,
    brand_name VARCHAR(100) NOT NULL,
    model_id UUID NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    manufacturing_year INTEGER NOT NULL CHECK (manufacturing_year > 0),
    vehicle_value NUMERIC(15,2) NOT NULL CHECK (vehicle_value > 0),
    engine_size_cc INTEGER NOT NULL CHECK (engine_size_cc > 0)
);
