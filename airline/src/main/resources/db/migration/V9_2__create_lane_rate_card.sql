-- M9 §3, §10 — the standard cost table per lane. General Cargo Rate (GCR) style: a per-kg rate that
-- steps down as chargeable weight crosses a break, a flat minimum charge below which the weight-based
-- rate never applies, and a fixed terminal handling fee added on top of every booking regardless of
-- weight (§10: "total = weight bracket rate + fixed handling fee").
CREATE TABLE lane_rate_card (
    id                          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    origin_hub                  VARCHAR(10)  NOT NULL,
    dest_hub                    VARCHAR(10)  NOT NULL,
    version                     VARCHAR(50)  NOT NULL,
    status                      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    min_charge_paise            BIGINT       NOT NULL,
    terminal_handling_paise     BIGINT       NOT NULL,
    -- Per-kg rate for chargeable weight under the first break (< 45kg), then each GCR weight break.
    rate_below_45kg_paise_per_kg BIGINT      NOT NULL,
    rate_q45_paise_per_kg        BIGINT      NOT NULL,   -- >= 45kg
    rate_q100_paise_per_kg       BIGINT      NOT NULL,   -- >= 100kg
    rate_q300_paise_per_kg       BIGINT      NOT NULL,   -- >= 300kg
    rate_q500_paise_per_kg       BIGINT      NOT NULL,   -- >= 500kg
    rate_q1000_paise_per_kg      BIGINT      NOT NULL,   -- >= 1000kg
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- At most one ACTIVE rate card per lane (mirrors M2's uq_rate_card_active_published).
CREATE UNIQUE INDEX uq_lane_rate_card_active
    ON lane_rate_card (origin_hub, dest_hub)
    WHERE status = 'ACTIVE';
