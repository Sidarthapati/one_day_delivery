-- M2 Costing: per-city ops inputs feeding the internal per-parcel cost floor (M2-D-004).
-- Internal only — never exposed to customers. One ACTIVE row per city.

CREATE TABLE costing_params (
    id                              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    city                            VARCHAR(10)     NOT NULL,
    version                         VARCHAR(50)     NOT NULL,
    status                          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    da_cost_per_shift_paise         BIGINT          NOT NULL,
    shift_hours                     DOUBLE PRECISION NOT NULL DEFAULT 8.0,
    utilisation_pct                 INTEGER         NOT NULL DEFAULT 70,
    avg_parcels_per_shift           INTEGER         NOT NULL,
    van_cost_per_run_paise          BIGINT          NOT NULL,
    avg_parcels_per_van_run         INTEGER         NOT NULL,
    hub_cost_per_parcel_paise       BIGINT          NOT NULL,
    airline_cost_per_parcel_paise   BIGINT          NOT NULL,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_costing_params_active
    ON costing_params (city)
    WHERE status = 'ACTIVE';
