-- Seed hub + airport coordinates and a starter fleet config for the 5 launch cities.
-- city_id UUIDs are the fixed ones shared with M3 (grid.cities / application.yml routing.cities).
-- Idempotent: ON CONFLICT DO UPDATE so re-runs refresh coords/config without duplicating rows.

INSERT INTO city_logistics_node (city_id, kind, lat, lon, name) VALUES
    -- Delhi
    ('f47ac10b-58cc-4372-a567-0e02b2c3d479', 'HUB',     28.6139, 77.2090, 'Delhi Hub'),
    ('f47ac10b-58cc-4372-a567-0e02b2c3d479', 'AIRPORT', 28.5562, 77.1000, 'Indira Gandhi Intl (DEL)'),
    -- Mumbai
    ('550e8400-e29b-41d4-a716-446655440000', 'HUB',     19.0760, 72.8777, 'Mumbai Hub'),
    ('550e8400-e29b-41d4-a716-446655440000', 'AIRPORT', 19.0896, 72.8656, 'Chhatrapati Shivaji Intl (BOM)'),
    -- Bangalore
    ('6ba7b810-9dad-11d1-80b4-00c04fd430c8', 'HUB',     12.9716, 77.5946, 'Bangalore Hub'),
    ('6ba7b810-9dad-11d1-80b4-00c04fd430c8', 'AIRPORT', 13.1986, 77.7066, 'Kempegowda Intl (BLR)'),
    -- Hyderabad
    ('6ba7b811-9dad-11d1-80b4-00c04fd430c8', 'HUB',     17.3850, 78.4867, 'Hyderabad Hub'),
    ('6ba7b811-9dad-11d1-80b4-00c04fd430c8', 'AIRPORT', 17.2403, 78.4294, 'Rajiv Gandhi Intl (HYD)'),
    -- Chennai
    ('6ba7b812-9dad-11d1-80b4-00c04fd430c8', 'HUB',     13.0827, 80.2707, 'Chennai Hub'),
    ('6ba7b812-9dad-11d1-80b4-00c04fd430c8', 'AIRPORT', 12.9941, 80.1709, 'Chennai Intl (MAA)')
ON CONFLICT (city_id, kind) DO UPDATE
    SET lat = EXCLUDED.lat, lon = EXCLUDED.lon, name = EXCLUDED.name;

INSERT INTO city_fleet_config
    (city_id, vans_available, capacity_packets, cycle_time_min_minutes, cycle_time_max_minutes,
     shuttle_cadence_minutes, max_da_to_vertex_minutes, dwell_minutes) VALUES
    ('f47ac10b-58cc-4372-a567-0e02b2c3d479', 6, 120, 120, 180, 30, 12, 10),
    ('550e8400-e29b-41d4-a716-446655440000', 6, 120, 120, 180, 30, 12, 10),
    ('6ba7b810-9dad-11d1-80b4-00c04fd430c8', 6, 120, 120, 180, 30, 12, 10),
    ('6ba7b811-9dad-11d1-80b4-00c04fd430c8', 6, 120, 120, 180, 30, 12, 10),
    ('6ba7b812-9dad-11d1-80b4-00c04fd430c8', 6, 120, 120, 180, 30, 12, 10)
ON CONFLICT (city_id) DO UPDATE
    SET vans_available           = EXCLUDED.vans_available,
        capacity_packets         = EXCLUDED.capacity_packets,
        cycle_time_min_minutes   = EXCLUDED.cycle_time_min_minutes,
        cycle_time_max_minutes   = EXCLUDED.cycle_time_max_minutes,
        shuttle_cadence_minutes  = EXCLUDED.shuttle_cadence_minutes,
        max_da_to_vertex_minutes = EXCLUDED.max_da_to_vertex_minutes,
        dwell_minutes            = EXCLUDED.dwell_minutes,
        updated_at               = now();
