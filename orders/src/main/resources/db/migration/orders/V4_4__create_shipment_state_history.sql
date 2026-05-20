-- Append-only; no UPDATE or DELETE ever issued against this table
CREATE TABLE shipment_state_history (
  id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
  shipment_id    UUID           NOT NULL REFERENCES shipments(id),
  from_state     shipment_state,
  to_state       shipment_state NOT NULL,
  triggered_by   VARCHAR(100)   NOT NULL,
  trigger_source VARCHAR(20)    NOT NULL,
  event_ref      VARCHAR(200),
  notes          TEXT,
  occurred_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_history_shipment_id ON shipment_state_history(shipment_id);
CREATE INDEX idx_history_occurred_at ON shipment_state_history(occurred_at);
