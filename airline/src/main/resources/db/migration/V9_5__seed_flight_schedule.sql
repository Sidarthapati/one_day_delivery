-- M9 seed: 4 daily departures (06:00/12:00/18:00/22:00 IST, mirroring M7's
-- StubFlightAssignmentPort cadence) on every directed lane across the 5 grid cities.
--
-- City codes (IATA, as M4/orders sends them — see pricing/V2_4's "as M4 sends them" note):
-- DEL Delhi, BOM Mumbai, BLR Bengaluru, HYD Hyderabad, MAA Chennai.
--
-- Carrier is a placeholder ('SIM-CARRIER') behind the simulated provider (§4) until a real airline
-- partner is signed (§12) — flight numbers/timetable are provisional, not a real published schedule.
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
)
INSERT INTO flight_schedule (origin_hub, dest_hub, carrier, flight_no, departure_time, arrival_time, capacity_kg)
SELECT l.origin_hub, l.dest_hub, 'SIM-CARRIER',
       'OD' || l.origin_hub || l.dest_hub || TO_CHAR(s.departure_time, 'HH24'),
       s.departure_time, s.arrival_time, 2000
FROM lanes l CROSS JOIN slots s
ON CONFLICT (flight_no) DO NOTHING;
