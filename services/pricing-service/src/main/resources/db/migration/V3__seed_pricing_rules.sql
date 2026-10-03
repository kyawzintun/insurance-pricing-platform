-- Educational values only, not actuarial pricing.
-- BASE_PREMIUM has no comparison operand; EQUALS is a storage placeholder.
-- Evaluation semantics will be specified with the future pricing engine.
INSERT INTO pricing_rules
    (id, rule_type, operator, comparison_value, comparison_value_to, factor,
     fixed_amount, effective_from, enabled, description, version, created_at, updated_at)
VALUES
    ('75ddf7df-77ce-57bc-8604-f2c0b95b76c5', 'BASE_PREMIUM', 'EQUALS', NULL, NULL, NULL, 8000.00, '2026-01-01T00:00:00Z', TRUE, 'Learning-only BASE_PREMIUM rule', 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
    ('1e506e1d-0cdd-583f-83ae-1d591a9ff53d', 'DRIVER_AGE', 'LESS_THAN', '25', NULL, 1.25, NULL, '2026-01-01T00:00:00Z', TRUE, 'Learning-only DRIVER_AGE rule', 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
    ('ffd37617-2f64-5f76-b447-4204ff778175', 'DRIVING_EXPERIENCE', 'LESS_THAN', '3', NULL, 1.15, NULL, '2026-01-01T00:00:00Z', TRUE, 'Learning-only DRIVING_EXPERIENCE rule', 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
    ('0bb997aa-f6a0-5d83-99d8-68364782a436', 'VEHICLE_AGE', 'GREATER_THAN', '10', NULL, 1.20, NULL, '2026-01-01T00:00:00Z', TRUE, 'Learning-only VEHICLE_AGE rule', 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
    ('91b5c588-041a-5fae-b18a-34a9694c6629', 'PREVIOUS_CLAIMS', 'GREATER_THAN_OR_EQUAL', '2', NULL, 1.30, NULL, '2026-01-01T00:00:00Z', TRUE, 'Learning-only PREVIOUS_CLAIMS rule', 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
    ('e8b09cf1-c3f3-57e3-a1fb-1dc907680679', 'COVERAGE_TYPE', 'EQUALS', 'COMPREHENSIVE', NULL, 1.40, NULL, '2026-01-01T00:00:00Z', TRUE, 'Learning-only COVERAGE_TYPE rule', 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
    ('2e72bb6f-dae6-5e79-996d-3babf046fff5', 'COVERAGE_TYPE', 'EQUALS', 'THIRD_PARTY', NULL, 1.00, NULL, '2026-01-01T00:00:00Z', TRUE, 'Learning-only COVERAGE_TYPE rule', 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
