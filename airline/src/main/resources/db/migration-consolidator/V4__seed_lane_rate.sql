-- Mock consolidator seed: one flat placeholder GCR-style rate per lane, same figures as our old
-- internally-negotiated card (₹1,500 min charge, ₹380 terminal handling, decreasing per-kg rate
-- across the standard weight breaks) — now living on "their" side instead of ours.
WITH cities(code) AS (VALUES ('DEL'), ('BOM'), ('BLR'), ('HYD'), ('MAA')),
lanes AS (
    SELECT o.code AS origin_hub, d.code AS dest_hub
    FROM cities o CROSS JOIN cities d
    WHERE o.code <> d.code
)
INSERT INTO lane_rate (
    origin_hub, dest_hub, version, status, min_charge_paise, terminal_handling_paise,
    rate_below_45kg_paise_per_kg, rate_q45_paise_per_kg, rate_q100_paise_per_kg,
    rate_q300_paise_per_kg, rate_q500_paise_per_kg, rate_q1000_paise_per_kg
)
SELECT origin_hub, dest_hub, 'v1.0', 'ACTIVE', 150000, 38000,
       6500, 5800, 5200, 4700, 4300, 4000
FROM lanes
ON CONFLICT DO NOTHING;
