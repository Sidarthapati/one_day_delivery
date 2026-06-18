-- Accumulation buffer: inbound parcel events (M7 sorted-for-delivery, M5 DA-pickup) land here so the
-- batch binder (§12) can run SLA-first over the current set. The consumers write; the ports read.
CREATE TABLE inbound_parcel (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind                VARCHAR(10) NOT NULL,            -- DELIVER | COLLECT
    parcel_id           UUID        NOT NULL,
    city_id             UUID        NOT NULL,
    da_id               UUID,                            -- COLLECT: source DA
    destination_hex_id  UUID,                            -- DELIVER: destination hex
    ready_at            TIMESTAMPTZ,                     -- DELIVER: sorted-for-delivery time
    picked_up_at        TIMESTAMPTZ,                     -- COLLECT: DA pickup time
    sla_deadline        TIMESTAMPTZ,                     -- DELIVER: delivery deadline
    valid_date          DATE        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_inbound_parcel UNIQUE (kind, parcel_id)  -- duplicate events are no-ops
);

CREATE INDEX idx_inbound_parcel_feed ON inbound_parcel (city_id, valid_date, kind);
