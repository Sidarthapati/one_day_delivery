-- M10 anti-fatigue (R4): when a manager acknowledges a fire, stamp it here so the priority scorer can
-- sink the parcel within its band for a cooldown — the queue keeps surfacing NEW fires, not the ones
-- already being worked. A worsening after the ack invalidates it (the scorer checks entered_state_at),
-- so a re-escalation resurfaces on its own.
ALTER TABLE sla_shipment
    ADD COLUMN acknowledged_at TIMESTAMPTZ,
    ADD COLUMN acknowledged_by VARCHAR(64);
