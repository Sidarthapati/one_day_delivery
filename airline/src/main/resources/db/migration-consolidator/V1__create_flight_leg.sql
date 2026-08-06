-- Mock of the freight consolidator's production schema: a concrete, date-specific flight leg —
-- NOT a recurring weekly pattern like our old (now-dropped) flight_schedule. Real consolidator
-- schedules vary leg to leg (capacity, carrier, even flight number can change week to week), which
-- is why they publish a rolling ~3-month calendar of actual legs rather than a repeating rule.
--
-- status is the consolidator's own operational word on the leg: SCHEDULED | DELAYED | CANCELLED.
-- DEPARTED/LANDED are not modeled here — M9 computes those itself from its own flight_instance
-- once departure/arrival Instants pass (see FlightStatusPollJob), it never asks the consolidator.
CREATE TABLE flight_leg (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    flight_no              VARCHAR(20)  NOT NULL,
    flight_date            DATE         NOT NULL,
    carrier                VARCHAR(30)  NOT NULL,
    origin_hub             VARCHAR(10)  NOT NULL,
    dest_hub               VARCHAR(10)  NOT NULL,
    departure_at           TIMESTAMPTZ  NOT NULL,
    arrival_at             TIMESTAMPTZ  NOT NULL,
    capacity_kg            INTEGER      NOT NULL,
    status                 VARCHAR(16)  NOT NULL DEFAULT 'SCHEDULED',
    estimated_departure_at TIMESTAMPTZ,
    estimated_arrival_at   TIMESTAMPTZ,
    CONSTRAINT uq_flight_leg UNIQUE (flight_no, flight_date)
);

CREATE INDEX idx_flight_leg_lane_date ON flight_leg (origin_hub, dest_hub, flight_date);
