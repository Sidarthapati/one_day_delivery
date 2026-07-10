-- M8 §4.1 — the append-only scan ledger: one immutable row per physical scan of a parcel, forever.
-- The single source of truth for "who touched this box, when, where" (design D-001: keyed on
-- shipment_id — the same UUID routing/hub already pass; the human barcode string in parcel_id is
-- layered on from LABEL_GENERATED onward). Two write paths feed it (design §4.3): REST lifecycle
-- scans (M8 PR3) and the in-process van custody port (M8 PR4). Never updated, never deleted.
CREATE TABLE scan_ledger (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id     UUID         NOT NULL,               -- spine: joins to orders.shipments.id
    parcel_id       VARCHAR(30),                         -- barcode string, NULL until LABEL_GENERATED
    scan_type       VARCHAR(24)  NOT NULL,               -- ScanEventType (6) ∪ VanScanType (4)
    location_type   VARCHAR(32)  NOT NULL,               -- HUB | VAN | DA | AIRPORT | CUSTOMER_COUNTER (CUSTOMER_COUNTER is 16 chars; 32 leaves headroom)
    location_id     UUID,                                -- hub / van / DA id
    actor_id        UUID,                                -- who held the gun
    counterparty_id UUID,                                -- the other party in a van handoff (the DA)
    scanned_at      TIMESTAMPTZ  NOT NULL,               -- device wall-clock (when it physically happened)
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(), -- server insert time
    client_scan_id  UUID                                 -- device idempotency key (design §4.1 / §6)
);

CREATE INDEX idx_scan_ledger_shipment ON scan_ledger (shipment_id, scanned_at);
-- Ordered by scanned_at so the "who touched this box, in order" trail lookup needs no sort.
CREATE INDEX idx_scan_ledger_parcel   ON scan_ledger (parcel_id, scanned_at) WHERE parcel_id IS NOT NULL;
-- Idempotency: a retried REST scan carrying the same client_scan_id is collapsed to one row.
-- NOTE: van custody scans (VAN_LOAD/VAN_TO_DA/DA_TO_VAN/VAN_UNLOAD) carry NO client_scan_id
-- (routing's VanCustodyScan has no such field), so this index does not dedup them. PR4's adapter
-- MUST guard van inserts with an existsByShipmentIdAndScanType read-check — there is no DB backstop
-- here on purpose (RTO re-loads can legitimately repeat a scan_type, so a hard unique index is wrong).
CREATE UNIQUE INDEX uq_scan_ledger_client ON scan_ledger (client_scan_id) WHERE client_scan_id IS NOT NULL;

-- M8's load-bearing invariant. Beyond the app's updatable=false mapping, this rejects any
-- UPDATE/DELETE at the DB — even raw SQL from another service — so the audit trail cannot be doctored.
CREATE OR REPLACE FUNCTION scan_ledger_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'scan_ledger is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_scan_ledger_append_only
    BEFORE UPDATE OR DELETE ON scan_ledger
    FOR EACH ROW EXECUTE FUNCTION scan_ledger_append_only();
