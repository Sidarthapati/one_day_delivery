-- M7 §14.3 seed — the physical stand POOL for each of the 5 launch hubs. Stands are real shelves on
-- the floor; they exist whether or not a bag is on them. There is NO pre-mapped destination→stand
-- directory: a stand becomes a destination's flight bag only when the first parcel for that flight
-- opens a bag (dynamic allocation, BagService). city_id == hub_id in v1. Deterministic md5(text)::uuid
-- ids so the seed is idempotent (ON CONFLICT (id) DO NOTHING).
-- The stand_no labels (A-*, D-*) and `zone` only mark WHERE on the floor a shelf physically sits
-- (near the airport dock vs the delivery dock). They do NOT pin a shelf to flight vs delivery use —
-- any bag can be allocated onto any free stand; zone is a soft preference layered on later.
INSERT INTO stand (id, city_id, hub_id, stand_no, zone, capacity, status)
SELECT md5(c.code || ':' || s.stand_no)::uuid, c.id, c.id, s.stand_no, s.zone, 200, 'OPEN'
FROM (VALUES
        ('DELHI',     'f47ac10b-58cc-4372-a567-0e02b2c3d479'::uuid),
        ('MUMBAI',    '550e8400-e29b-41d4-a716-446655440000'::uuid),
        ('BANGALORE', '6ba7b810-9dad-11d1-80b4-00c04fd430c8'::uuid),
        ('HYDERABAD', '6ba7b811-9dad-11d1-80b4-00c04fd430c8'::uuid),
        ('CHENNAI',   '6ba7b812-9dad-11d1-80b4-00c04fd430c8'::uuid)
     ) AS c(code, id)
CROSS JOIN (VALUES
        ('A-1', 'AIRPORT_DOCK'),
        ('A-2', 'AIRPORT_DOCK'),
        ('A-3', 'AIRPORT_DOCK'),
        ('A-4', 'AIRPORT_DOCK'),
        ('A-5', 'AIRPORT_DOCK'),
        ('A-6', 'AIRPORT_DOCK'),
        ('A-7', 'AIRPORT_DOCK'),
        ('A-8', 'AIRPORT_DOCK'),
        ('D-1', 'DELIVERY_DOCK'),
        ('D-2', 'DELIVERY_DOCK')
     ) AS s(stand_no, zone)
ON CONFLICT (id) DO NOTHING;
