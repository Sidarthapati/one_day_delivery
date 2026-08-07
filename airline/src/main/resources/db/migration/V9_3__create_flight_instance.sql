-- M9 §3 — a specific date's occurrence of a flight_schedule slot, once it's actually being used to
-- carry parcels. booked_weight_grams is the running per-flight weight commitment (§3, §5's "honest
-- limitation": an estimate while a bag is still filling, corrected to the real number on each booking
-- at seal time). Kept in grams (not capacity_kg's whole kilos) so repeated increments from each bag's
-- exact weight never lose precision.
CREATE TABLE flight_instance (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    flight_no           VARCHAR(20)  NOT NULL,
    flight_date         DATE         NOT NULL,
    origin_hub          VARCHAR(10)  NOT NULL,
    dest_hub            VARCHAR(10)  NOT NULL,
    departure           TIMESTAMPTZ  NOT NULL,
    arrival             TIMESTAMPTZ  NOT NULL,
    cutoff              TIMESTAMPTZ  NOT NULL,
    capacity_kg         INTEGER      NOT NULL,
    booked_weight_grams INTEGER      NOT NULL DEFAULT 0,
    -- SCHEDULED | DEPARTED | LANDED | CANCELLED. Only SCHEDULED/DEPARTED/LANDED are used this milestone.
    status        VARCHAR(16)  NOT NULL DEFAULT 'SCHEDULED',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_flight_instance UNIQUE (flight_no, flight_date)
);

CREATE INDEX idx_flight_instance_lane_date ON flight_instance (origin_hub, dest_hub, flight_date);
