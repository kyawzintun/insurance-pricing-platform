-- Global ID pagination cannot use the customer-leading history index.
CREATE INDEX idx_quotes_created_id ON quotes (created_at DESC, id DESC);
