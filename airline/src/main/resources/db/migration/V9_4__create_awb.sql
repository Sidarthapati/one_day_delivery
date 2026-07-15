-- M9 §3, §6 — the record of a finished booking: a hub's sealed flight bag, booked onto one flight as
-- one confirmed reservation. bag_id is unique — the booking is idempotent per sealed bag, since the
-- triggering BAG_SEALED notification can redeliver (§11: "the same batch sealed notice arriving
-- twice"). superseded_by links an old booking to its replacement when a flight changes (later phase;
-- column exists now so that work doesn't need a migration of its own).
CREATE TABLE awb (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    awb_no              VARCHAR(30)  NOT NULL,
    flight_no           VARCHAR(20)  NOT NULL,
    flight_date         DATE         NOT NULL,
    origin_hub          VARCHAR(10)  NOT NULL,
    dest_hub            VARCHAR(10)  NOT NULL,
    bag_id              UUID         NOT NULL,
    total_weight_grams  INTEGER      NOT NULL,
    parcel_count        INTEGER      NOT NULL,
    cost_paise          BIGINT       NOT NULL,
    provider_ref        VARCHAR(50)  NOT NULL,
    -- BOOKED | SUPERSEDED | CANCELLED. Only BOOKED is produced this milestone.
    status              VARCHAR(16)  NOT NULL DEFAULT 'BOOKED',
    superseded_by       UUID         REFERENCES awb(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_awb_no UNIQUE (awb_no),
    CONSTRAINT uq_awb_bag UNIQUE (bag_id)
);

CREATE INDEX idx_awb_flight ON awb (flight_no, flight_date);
