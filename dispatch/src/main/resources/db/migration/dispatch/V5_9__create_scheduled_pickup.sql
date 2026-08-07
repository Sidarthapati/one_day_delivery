-- M5 scheduled pickups: an order with a future pickup slot (or booked off-hours) is HELD here instead
-- of entering a DA queue immediately, so it doesn't accrue false wait-time before it's due. The release
-- job promotes each row into normal assignment ~60 min before its slot (release_at).
CREATE TABLE scheduled_pickup (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    shipment_id   UUID NOT NULL,
    city_id       UUID NOT NULL,
    tile_id       UUID NOT NULL,
    pickup_lat    DOUBLE PRECISION NOT NULL,
    pickup_lon    DOUBLE PRECISION NOT NULL,
    payment_mode  VARCHAR(20),
    slot_start    TIMESTAMPTZ,                -- null for ASAP-off-hours holds
    slot_end      TIMESTAMPTZ,
    release_at    TIMESTAMPTZ NOT NULL,       -- slot_start − releaseLeadMinutes (or next operating start)
    status        VARCHAR(20) NOT NULL DEFAULT 'HELD',  -- HELD | RELEASED | CANCELLED
    released_at   TIMESTAMPTZ
);

-- Hot query for the release job: HELD rows now due.
CREATE INDEX idx_scheduled_pickup_release ON scheduled_pickup (status, release_at)
    WHERE status = 'HELD';

-- One live hold per shipment.
CREATE UNIQUE INDEX ux_scheduled_pickup_shipment ON scheduled_pickup (shipment_id)
    WHERE status = 'HELD';
