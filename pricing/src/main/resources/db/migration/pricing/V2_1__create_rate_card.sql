-- M2 Pricing: versioned rate cards.
-- B2C/C2C have one ACTIVE published card each; every B2B account references its own card by id
-- (orders.b2b_accounts.rate_card_id). Cards are never mutated in place — a rate change ships as a
-- new ACTIVE version and the prior one is flipped to SUPERSEDED, so historical shipments always
-- re-price against the version snapshot stored on them (M2-D-002).

CREATE TABLE rate_card (
    id                          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code                        VARCHAR(50)  NOT NULL,
    customer_type               VARCHAR(10)  NOT NULL,
    version                     VARCHAR(50)  NOT NULL,
    status                      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    effective_from              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    effective_to                TIMESTAMPTZ,
    currency                    VARCHAR(3)   NOT NULL DEFAULT 'INR',
    slab_grams                  INTEGER      NOT NULL DEFAULT 500,
    volumetric_divisor          INTEGER      NOT NULL DEFAULT 5000,
    first_slab_pct              INTEGER      NOT NULL DEFAULT 100,
    slab_decrement_pct          INTEGER      NOT NULL DEFAULT 10,
    slab_floor_pct              INTEGER      NOT NULL DEFAULT 60,
    discount_bps                INTEGER      NOT NULL DEFAULT 0,
    gst_bps                     INTEGER      NOT NULL DEFAULT 1800,
    cod_pct_bps                 INTEGER      NOT NULL DEFAULT 150,
    cod_min_paise               BIGINT       NOT NULL DEFAULT 5000,
    same_city_base_price_paise  BIGINT       NOT NULL DEFAULT 5000,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- At most one ACTIVE published card per customer type. B2B cards (one per account) are exempt.
CREATE UNIQUE INDEX uq_rate_card_active_published
    ON rate_card (customer_type)
    WHERE status = 'ACTIVE' AND customer_type IN ('B2C', 'C2C');
