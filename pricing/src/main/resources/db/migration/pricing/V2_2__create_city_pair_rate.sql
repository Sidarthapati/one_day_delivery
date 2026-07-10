-- M2 Pricing: per-card city-pair base prices (price in paise for the first 0.5 kg slab).
-- Seeded in both directions (the published sheet is symmetric). One row per (card, origin, dest).

CREATE TABLE city_pair_rate (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    rate_card_id      UUID         NOT NULL REFERENCES rate_card(id),
    origin_city       VARCHAR(10)  NOT NULL,
    dest_city         VARCHAR(10)  NOT NULL,
    base_price_paise  BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_city_pair_rate UNIQUE (rate_card_id, origin_city, dest_city)
);

CREATE INDEX idx_city_pair_rate_card ON city_pair_rate (rate_card_id);
