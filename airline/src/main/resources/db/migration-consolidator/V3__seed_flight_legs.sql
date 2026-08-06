-- Mock ~3-month consolidator schedule: 4 daily departures (06:00/12:00/18:00/22:00 IST) on every
-- directed lane across the 5 grid cities, materialized as concrete dated legs (today .. +89 days) —
-- NOT a recurring weekly rule, since a real consolidator's published calendar varies leg to leg.
--
-- Each leg's status is a deterministic function of (flight_no, flight_date) via an md5-based bucket
-- (0-99): bucket<3 -> CANCELLED, bucket<10 -> DELAYED (mirrors the old simulated provider's ~3%/~7%
-- split), else SCHEDULED. Deterministic so re-running this seed (or the roll-forward job inserting
-- further-out dates later) always agrees with itself — never a random flip on an existing row.
WITH cities(code) AS (VALUES ('DEL'), ('BOM'), ('BLR'), ('HYD'), ('MAA')),
lanes AS (
    SELECT o.code AS origin_hub, d.code AS dest_hub
    FROM cities o CROSS JOIN cities d
    WHERE o.code <> d.code
),
slots(departure_time, arrival_time) AS (
    VALUES
        ('06:00'::time, '08:00'::time),
        ('12:00'::time, '14:00'::time),
        ('18:00'::time, '20:00'::time),
        ('22:00'::time, '00:00'::time)
),
dates AS (
    SELECT generate_series(CURRENT_DATE, CURRENT_DATE + 89, INTERVAL '1 day')::date AS flight_date
),
legs AS (
    SELECT
        l.origin_hub, l.dest_hub, 'SIM-CONSOLIDATOR' AS carrier,
        'CS' || l.origin_hub || l.dest_hub || TO_CHAR(s.departure_time, 'HH24') AS flight_no,
        d.flight_date, s.departure_time, s.arrival_time,
        2000 AS capacity_kg,
        (('x' || substr(md5(
            'CS' || l.origin_hub || l.dest_hub || TO_CHAR(s.departure_time, 'HH24') || d.flight_date::text
        ), 1, 8))::bit(32)::int % 100 + 100) % 100 AS bucket
    FROM lanes l CROSS JOIN slots s CROSS JOIN dates d
)
INSERT INTO flight_leg (
    flight_no, flight_date, carrier, origin_hub, dest_hub, departure_at, arrival_at, capacity_kg,
    status, estimated_departure_at, estimated_arrival_at
)
SELECT
    flight_no, flight_date, carrier, origin_hub, dest_hub,
    (flight_date + departure_time) AT TIME ZONE 'Asia/Kolkata',
    -- overnight-spanning slot (22:00 -> 00:00): arrival lands the next day
    (CASE WHEN arrival_time > departure_time THEN flight_date ELSE flight_date + 1 END + arrival_time)
        AT TIME ZONE 'Asia/Kolkata',
    capacity_kg,
    CASE WHEN bucket < 3 THEN 'CANCELLED' WHEN bucket < 10 THEN 'DELAYED' ELSE 'SCHEDULED' END,
    CASE WHEN bucket >= 3 AND bucket < 10
         THEN (flight_date + departure_time) AT TIME ZONE 'Asia/Kolkata' + ((30 + bucket % 90) || ' minutes')::interval
         ELSE NULL END,
    CASE WHEN bucket >= 3 AND bucket < 10
         THEN (CASE WHEN arrival_time > departure_time THEN flight_date ELSE flight_date + 1 END + arrival_time)
                  AT TIME ZONE 'Asia/Kolkata' + ((30 + bucket % 90) || ' minutes')::interval
         ELSE NULL END
FROM legs
ON CONFLICT (flight_no, flight_date) DO NOTHING;
