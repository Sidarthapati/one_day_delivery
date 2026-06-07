-- One ordered stop per (van, loop) of a plan. hex_vertex_id is M3's h3_hex_vertex.id (plain UUID,
-- no cross-module FK). planned arrival/departure are wall-clock times-of-day (07:00–20:00 window).
CREATE TABLE route_plan_stop (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_plan_id      UUID        NOT NULL REFERENCES route_plan(id),
    van_id             UUID        NOT NULL,
    loop_index         INT         NOT NULL,
    stop_seq           INT         NOT NULL,
    node_kind          VARCHAR(20) NOT NULL,            -- HUB | MEETING_VERTEX
    hex_vertex_id      UUID,
    planned_arrival    TIME,
    planned_departure  TIME,
    deliver_qty        INT         NOT NULL DEFAULT 0,
    collect_qty        INT         NOT NULL DEFAULT 0,
    load_after         INT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_route_plan_stop_plan_van_loop ON route_plan_stop (route_plan_id, van_id, loop_index);
