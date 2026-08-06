-- M10 — SLA monitoring & escalation.
-- Self-contained (M10-D-007): M10 owns these tables and reads other modules only via the event bus.
-- Enum-like columns are VARCHAR + app-side @Enumerated(STRING) (no Postgres enum type coupling).

-- ── Per-shipment SLA rollup (mutable) ──────────────────────────────────────
CREATE TABLE sla_shipment (
  id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  shipment_id          UUID         NOT NULL UNIQUE,
  shipment_ref         VARCHAR(64),
  origin_city          VARCHAR(50),
  dest_city            VARCHAR(50),
  lane                 VARCHAR(24),                         -- e.g. DELHI-MUMBAI (derived)
  delivery_type        VARCHAR(20),                         -- INTERCITY | SAME_CITY
  booked_at            TIMESTAMPTZ  NOT NULL,               -- clock start (M10-D-006)
  internal_target_at   TIMESTAMPTZ  NOT NULL,               -- booked_at + 16h
  public_promise_at    TIMESTAMPTZ  NOT NULL,               -- booked_at + 24h
  eta_promised         TIMESTAMPTZ,                         -- M4's customer-shown ETA (reference)
  overall_state        VARCHAR(16)  NOT NULL DEFAULT 'GREEN',
  projected_finish_at  TIMESTAMPTZ,
  current_leg          VARCHAR(24),
  delivered_at         TIMESTAMPTZ,
  closed_at            TIMESTAMPTZ,
  breached             BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sla_shipment_state ON sla_shipment(overall_state);
CREATE INDEX idx_sla_shipment_open  ON sla_shipment(overall_state) WHERE closed_at IS NULL;
CREATE INDEX idx_sla_shipment_city  ON sla_shipment(origin_city, dest_city);

-- ── Per-shipment, per-leg SLA row (mutable) ────────────────────────────────
CREATE TABLE sla_leg (
  id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  shipment_id        UUID         NOT NULL,
  leg                VARCHAR(24)  NOT NULL,                 -- SlaLegType
  seq                INT          NOT NULL,
  budget_minutes     INT          NOT NULL,
  started_at         TIMESTAMPTZ,
  deadline_at        TIMESTAMPTZ,
  completed_at       TIMESTAMPTZ,
  state              VARCHAR(16)  NOT NULL DEFAULT 'GREEN',
  projected_end_at   TIMESTAMPTZ,
  source_event       VARCHAR(64),
  created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_sla_leg UNIQUE (shipment_id, leg)
);
CREATE INDEX idx_sla_leg_shipment ON sla_leg(shipment_id);
CREATE INDEX idx_sla_leg_open ON sla_leg(deadline_at) WHERE completed_at IS NULL;

-- ── Append-only escalation raise log (no UPDATE/DELETE ever issued) ─────────
CREATE TABLE sla_escalation (
  id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  shipment_id          UUID         NOT NULL,
  shipment_ref         VARCHAR(64),
  leg                  VARCHAR(24),
  from_state           VARCHAR(16),
  to_state             VARCHAR(16)  NOT NULL,               -- RED | BREACHED
  level                VARCHAR(20)  NOT NULL,               -- SUPERVISOR|STATION_MANAGER|ADMIN
  city                 VARCHAR(50),
  reason_code          VARCHAR(48),
  projected_finish_at  TIMESTAMPTZ,
  created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sla_esc_shipment ON sla_escalation(shipment_id);
CREATE INDEX idx_sla_esc_city ON sla_escalation(city, created_at);

-- ── Append-only human action log against an escalation (ack/resolve/note) ──
CREATE TABLE sla_action (
  id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  escalation_id  UUID         NOT NULL REFERENCES sla_escalation(id),
  shipment_id    UUID         NOT NULL,
  action         VARCHAR(24)  NOT NULL,                     -- ACKNOWLEDGE | RESOLVE | NOTE
  acted_by       VARCHAR(64)  NOT NULL,                     -- M1 user id
  acted_by_role  VARCHAR(32),
  notes          TEXT,
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sla_action_escalation ON sla_action(escalation_id);
