CREATE TABLE h3_hex_demand_snapshot (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hex_id                UUID NOT NULL REFERENCES h3_hex(id),
    snapshot_date         DATE NOT NULL,
    hist_avg_orders       DOUBLE PRECISION NOT NULL,
    current_orders        INT  NOT NULL,
    demand_score_orders   DOUBLE PRECISION NOT NULL,
    service_time_min      DOUBLE PRECISION NOT NULL,
    inter_stop_travel_min DOUBLE PRECISION NOT NULL,
    order_engaged_min     DOUBLE PRECISION NOT NULL,
    demand_score_minutes  DOUBLE PRECISION NOT NULL,
    is_bootstrapped       BOOLEAN NOT NULL DEFAULT false,
    UNIQUE(hex_id, snapshot_date)
);
