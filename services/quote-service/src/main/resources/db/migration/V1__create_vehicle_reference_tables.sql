CREATE TABLE vehicle_brands (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE vehicle_models (
    id UUID PRIMARY KEY,
    brand_id UUID NOT NULL REFERENCES vehicle_brands (id),
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (brand_id, name)
);
-- The UNIQUE (brand_id, name) index also supports lookups by brand_id.
