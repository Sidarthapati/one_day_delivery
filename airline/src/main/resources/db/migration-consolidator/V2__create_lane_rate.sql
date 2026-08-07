-- Mock of the consolidator's own per-lane rate table (replaces our old, internally-negotiated
-- lane_rate_card — pricing is now read directly from their system too). Same GCR-style shape: a
-- per-kg rate that steps down as chargeable weight crosses a break, a flat minimum charge, and a
-- fixed terminal handling fee on top of every booking.
CREATE TABLE lane_rate (
    id                           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    origin_hub                   VARCHAR(10)  NOT NULL,
    dest_hub                     VARCHAR(10)  NOT NULL,
    version                      VARCHAR(50)  NOT NULL,
    status                       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    min_charge_paise             BIGINT       NOT NULL,
    terminal_handling_paise      BIGINT       NOT NULL,
    rate_below_45kg_paise_per_kg BIGINT       NOT NULL,
    rate_q45_paise_per_kg        BIGINT       NOT NULL,
    rate_q100_paise_per_kg       BIGINT       NOT NULL,
    rate_q300_paise_per_kg       BIGINT       NOT NULL,
    rate_q500_paise_per_kg       BIGINT       NOT NULL,
    rate_q1000_paise_per_kg      BIGINT       NOT NULL
);

-- At most one ACTIVE rate per lane.
CREATE UNIQUE INDEX uq_lane_rate_active
    ON lane_rate (origin_hub, dest_hub)
    WHERE status = 'ACTIVE';
