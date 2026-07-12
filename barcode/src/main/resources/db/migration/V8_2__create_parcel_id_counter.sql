-- M8 §3.2 — per-hub, per-day sequence backing the seq6 suffix of the parcel barcode
-- (1DD-{destHubIATA}-{yyMMdd}-{seq6}). Same row-lock pattern as orders.shipment_ref_counters:
-- INSERT ... ON CONFLICT DO NOTHING to materialise the row, then SELECT FOR UPDATE to increment
-- (M8 PR2 owns the generation logic; this PR only provides the table).
CREATE TABLE parcel_id_counter (
    hub_iata  VARCHAR(3) NOT NULL,
    day       DATE       NOT NULL,
    next_seq  INTEGER    NOT NULL DEFAULT 1,
    PRIMARY KEY (hub_iata, day)
);
