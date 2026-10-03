-- Fixed educational catalog. UUIDs and timestamps are stable across rebuilds.
INSERT INTO vehicle_brands (id, name, active, created_at) VALUES
    ('f0a67386-c7ae-562e-ae46-9fe3042cf38e', 'Toyota', TRUE, '2026-01-01T00:00:00Z'),
    ('d923f496-cbb0-5b3b-b022-9dbe0a32aea9', 'Honda', TRUE, '2026-01-01T00:00:00Z'),
    ('d910e5d4-f97f-5645-a657-d4efce2a84de', 'Mazda', TRUE, '2026-01-01T00:00:00Z'),
    ('67ef2805-eee4-521b-9582-957ea0a310cc', 'Nissan', TRUE, '2026-01-01T00:00:00Z');

INSERT INTO vehicle_models (id, brand_id, name, active, created_at) VALUES
    ('b5c09895-4bde-5736-b549-d98cbdae7b1c', 'f0a67386-c7ae-562e-ae46-9fe3042cf38e', 'Yaris', TRUE, '2026-01-01T00:00:00Z'),
    ('4a2f1ced-8f30-50ce-8cf3-c58fa2c52216', 'f0a67386-c7ae-562e-ae46-9fe3042cf38e', 'Corolla', TRUE, '2026-01-01T00:00:00Z'),
    ('7966e042-1127-5b17-8e0c-a6799b4e67d0', 'f0a67386-c7ae-562e-ae46-9fe3042cf38e', 'Camry', TRUE, '2026-01-01T00:00:00Z'),
    ('14bc7b97-3180-5d44-b7f3-a8f9995e2543', 'd923f496-cbb0-5b3b-b022-9dbe0a32aea9', 'City', TRUE, '2026-01-01T00:00:00Z'),
    ('a194a4b2-7b90-5e30-b051-d1cafae353bf', 'd923f496-cbb0-5b3b-b022-9dbe0a32aea9', 'Civic', TRUE, '2026-01-01T00:00:00Z'),
    ('e4c907b8-0413-563d-8d01-96cd066c836d', 'd923f496-cbb0-5b3b-b022-9dbe0a32aea9', 'Accord', TRUE, '2026-01-01T00:00:00Z'),
    ('934db35c-6d71-5707-8c18-a0e1f5d08257', 'd910e5d4-f97f-5645-a657-d4efce2a84de', 'Mazda 2', TRUE, '2026-01-01T00:00:00Z'),
    ('150020c3-5ee2-55af-9aa2-c34092a15e2c', 'd910e5d4-f97f-5645-a657-d4efce2a84de', 'Mazda 3', TRUE, '2026-01-01T00:00:00Z'),
    ('5923cd33-0d3c-5204-8aa7-991bc8687638', 'd910e5d4-f97f-5645-a657-d4efce2a84de', 'CX-5', TRUE, '2026-01-01T00:00:00Z'),
    ('abf552f4-0298-5262-b189-3ea3b010df69', '67ef2805-eee4-521b-9582-957ea0a310cc', 'Almera', TRUE, '2026-01-01T00:00:00Z'),
    ('0ad0556f-cd30-50a5-b855-068a20fcafd6', '67ef2805-eee4-521b-9582-957ea0a310cc', 'Sylphy', TRUE, '2026-01-01T00:00:00Z'),
    ('48903b32-7811-5a5e-864b-5184e8df99d2', '67ef2805-eee4-521b-9582-957ea0a310cc', 'X-Trail', TRUE, '2026-01-01T00:00:00Z');
