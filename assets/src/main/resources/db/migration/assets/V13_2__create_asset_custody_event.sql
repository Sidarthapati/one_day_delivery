-- M13 custody ledger: one immutable row per asset exchange, forever. The audit trail and the source of
-- an asset's chain of custody. Append-only by construction (no setters, updatable=false, DB trigger
-- rejects UPDATE/DELETE) — mirrors scan_ledger (V8_1). The asset's current_holder_* pointer moves in the
-- same transaction; this history never mutates. actor_id / *_holder_id are FK-less UUIDs so the trail
-- survives user deletion (same convention as scan_ledger / role_audit_logs).
CREATE TABLE asset_custody_event (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id           UUID         NOT NULL REFERENCES asset (id),
    event_type         VARCHAR(28)  NOT NULL,   -- REGISTERED|ISSUED|RETURNED|TRANSFERRED|ACKNOWLEDGED|SENT_TO_MAINTENANCE|RETURNED_FROM_MAINTENANCE|REPORTED_LOST|REPORTED_DAMAGED|RECOVERED|DECOMMISSIONED
    from_holder_type   VARCHAR(16),             -- STATION | USER | VENDOR (null on REGISTERED)
    from_holder_id     UUID,
    from_holder_name   VARCHAR(160),
    to_holder_type     VARCHAR(16),
    to_holder_id       UUID,
    to_holder_name     VARCHAR(160),
    condition          VARCHAR(16),             -- condition asserted at this exchange
    actor_id           UUID         NOT NULL,   -- who recorded it (station manager or the DA)
    reason             TEXT,
    evidence_url       TEXT,                    -- reserved: condition photo at handoff
    city_id            UUID         NOT NULL,   -- denormalized scope
    client_event_id    VARCHAR(64),             -- device/UI idempotency key
    occurred_at        TIMESTAMPTZ  NOT NULL,
    recorded_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The custody chain of one asset, in order — no sort needed.
CREATE INDEX idx_asset_custody_asset  ON asset_custody_event (asset_id, recorded_at);
-- "Everything ever handed to this holder."
CREATE INDEX idx_asset_custody_to     ON asset_custody_event (to_holder_type, to_holder_id);
CREATE INDEX idx_asset_custody_city   ON asset_custody_event (city_id, event_type);
-- Idempotency: a retried write carrying the same client_event_id collapses to one row.
CREATE UNIQUE INDEX uq_asset_custody_client ON asset_custody_event (client_event_id) WHERE client_event_id IS NOT NULL;

-- Load-bearing invariant: reject any UPDATE/DELETE at the DB — even raw SQL — so custody can't be doctored.
CREATE OR REPLACE FUNCTION asset_custody_event_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'asset_custody_event is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_asset_custody_event_append_only
    BEFORE UPDATE OR DELETE ON asset_custody_event
    FOR EACH ROW EXECUTE FUNCTION asset_custody_event_append_only();
