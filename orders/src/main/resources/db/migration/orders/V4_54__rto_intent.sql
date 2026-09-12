-- Mid-transit RTO intent (feature iii). A return can now be requested from any in-custody state,
-- not only after a failed delivery attempt. The intent is recorded on the original shipment and
-- resolved when the parcel next reaches a hub (origin hub → same-city return; dest hub → reverse-lane
-- return). Nullable columns; existing rows keep NULL (no intent).
ALTER TABLE shipments
    ADD COLUMN rto_requested_at  TIMESTAMPTZ,
    ADD COLUMN rto_requested_by  VARCHAR(64),
    ADD COLUMN rto_reason        VARCHAR(500),
    ADD COLUMN rto_resolved_at   TIMESTAMPTZ;

-- Fast lookup of shipments with an unresolved RTO intent (the resolver checks this on hub arrival).
CREATE INDEX idx_shipment_rto_pending
    ON shipments (rto_requested_at)
    WHERE rto_requested_at IS NOT NULL AND rto_resolved_at IS NULL;
