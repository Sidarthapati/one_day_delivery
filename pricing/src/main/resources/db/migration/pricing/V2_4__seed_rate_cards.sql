-- M2 Pricing seed: published B2C + C2C cards, the demo B2B account card, the full city-pair matrix
-- from the published rate sheet (price in ₹ for the first 0.5 kg, stored in paise), and per-city
-- costing params. Re-runnable (ON CONFLICT DO NOTHING).
--
-- City codes (IATA, as M4 sends them): DEL Delhi, BOM Mumbai, HYD Hyderabad, BLR Bengaluru,
-- CCU Kolkata, BBI Bhubaneswar, IXC Chandigarh, GAU Guwahati.
-- NOTE: Chennai (MAA) is serviceable in the grid but absent from the rate sheet — intercity bookings
-- touching MAA return "no rate configured" until ops adds rows.

-- ── Rate cards ────────────────────────────────────────────────────────────────
-- Param columns (slab/gst/cod) take table defaults that mirror the sheet. B2B demo card gets the
-- fixed id referenced by orders.b2b_accounts (V4_13) and a 15% negotiated discount.
INSERT INTO rate_card (id, code, customer_type, version, status, discount_bps) VALUES
    ('c0000000-0000-0000-0000-0000000000c1', 'B2C-PUBLISHED', 'B2C', 'v1.0', 'ACTIVE', 0),
    ('c0000000-0000-0000-0000-0000000000c2', 'C2C-PUBLISHED', 'C2C', 'v1.0', 'ACTIVE', 0),
    ('c0000000-0000-0000-0000-000000000001', 'B2B-ACME-DEMO', 'B2B', 'v1.0', 'ACTIVE', 1500)
ON CONFLICT (id) DO NOTHING;

-- ── City-pair matrix → both directions × all three cards ────────────────────────
WITH pairs(a, b, rupees) AS (
    VALUES
        ('BBI','DEL',157),
        ('CCU','DEL',145), ('CCU','BBI',161),
        ('BOM','DEL',145), ('BOM','BBI',149), ('BOM','CCU',128),
        ('HYD','DEL',162), ('HYD','BBI',140), ('HYD','CCU',136), ('HYD','BOM',145),
        ('BLR','DEL',157), ('BLR','BBI',149), ('BLR','CCU',123), ('BLR','BOM',136), ('BLR','HYD',148),
        ('IXC','DEL',162), ('IXC','BBI',160), ('IXC','CCU',140), ('IXC','BOM',134), ('IXC','HYD',155), ('IXC','BLR',139),
        ('GAU','DEL',191), ('GAU','BBI',179), ('GAU','CCU',177), ('GAU','BOM',183), ('GAU','HYD',170), ('GAU','BLR',174), ('GAU','IXC',180)
),
cards(card_id) AS (
    VALUES
        ('c0000000-0000-0000-0000-0000000000c1'::uuid),
        ('c0000000-0000-0000-0000-0000000000c2'::uuid),
        ('c0000000-0000-0000-0000-000000000001'::uuid)
),
directed AS (
    SELECT a AS origin, b AS dest, rupees FROM pairs
    UNION ALL
    SELECT b AS origin, a AS dest, rupees FROM pairs
)
INSERT INTO city_pair_rate (rate_card_id, origin_city, dest_city, base_price_paise)
SELECT c.card_id, d.origin, d.dest, d.rupees * 100
FROM cards c CROSS JOIN directed d
ON CONFLICT ON CONSTRAINT uq_city_pair_rate DO NOTHING;

-- ── Per-city costing params (internal cost floor) ───────────────────────────────
-- Plausible v1 ops figures: DA ₹1200/shift, ~40 parcels nameplate @70% util; van ₹3000/120 parcels;
-- hub ₹15/parcel; airline ₹40/parcel. Floor ≈ ₹122/parcel.
INSERT INTO costing_params (
    id, city, version, status,
    da_cost_per_shift_paise, shift_hours, utilisation_pct, avg_parcels_per_shift,
    van_cost_per_run_paise, avg_parcels_per_van_run,
    hub_cost_per_parcel_paise, airline_cost_per_parcel_paise
) VALUES
    ('c0000000-0000-0000-0000-00000000d001', 'DEL', 'v1.0', 'ACTIVE', 120000, 8.0, 70, 40, 300000, 120, 1500, 4000),
    ('c0000000-0000-0000-0000-00000000d002', 'BOM', 'v1.0', 'ACTIVE', 130000, 8.0, 70, 40, 320000, 120, 1500, 4000),
    ('c0000000-0000-0000-0000-00000000d003', 'BLR', 'v1.0', 'ACTIVE', 120000, 8.0, 70, 40, 300000, 120, 1500, 4000),
    ('c0000000-0000-0000-0000-00000000d004', 'HYD', 'v1.0', 'ACTIVE', 115000, 8.0, 70, 40, 290000, 120, 1500, 4000),
    ('c0000000-0000-0000-0000-00000000d005', 'MAA', 'v1.0', 'ACTIVE', 118000, 8.0, 70, 40, 295000, 120, 1500, 4000)
ON CONFLICT (id) DO NOTHING;
