-- M9 §3 — the lane timetable: which flights run on an origin→dest lane, what time they leave/land,
-- and which days. A "flight_schedule" row is a recurring slot (e.g. "the 06:00 DEL→BOM every day");
-- V9_3's flight_instance is a specific date's occurrence of one of these slots.
CREATE TABLE flight_schedule (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    origin_hub      VARCHAR(10)  NOT NULL,
    dest_hub        VARCHAR(10)  NOT NULL,
    carrier         VARCHAR(30)  NOT NULL,
    flight_no       VARCHAR(20)  NOT NULL,
    departure_time  TIME         NOT NULL,   -- IST wall-clock
    arrival_time    TIME         NOT NULL,   -- IST wall-clock
    -- Bitmask, bit0=Monday..bit6=Sunday; 127 = every day.
    days_of_week    SMALLINT     NOT NULL DEFAULT 127,
    capacity_kg     INTEGER      NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_flight_schedule_no UNIQUE (flight_no)
);

CREATE INDEX idx_flight_schedule_lane ON flight_schedule (origin_hub, dest_hub, active);
