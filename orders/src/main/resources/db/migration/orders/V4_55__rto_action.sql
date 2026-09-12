-- Hub RTO worklist (feature iii). When a return is initiated, the return hub (origin hub for a
-- same-city return, dest hub for a reverse-lane return) gets a work item telling the worker to
-- physically turn the parcel around: pull it from its open flight bag (if it was bagged) and
-- dock-receive the return child so it sorts back. One OPEN row per return; closed when the child
-- leaves the hub (sorted) or a worker marks it done.
CREATE TABLE rto_action (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_ref          VARCHAR(30) NOT NULL,
    child_ref             VARCHAR(30) NOT NULL UNIQUE,
    return_hub_city       VARCHAR(10) NOT NULL,   -- IATA code of the hub that must action it
    lane                  VARCHAR(32) NOT NULL,   -- REVERSE_FROM_DEST | SAME_CITY_FROM_ORIGIN
    needs_bag_pull        BOOLEAN NOT NULL DEFAULT FALSE,
    status                VARCHAR(16) NOT NULL DEFAULT 'OPEN',   -- OPEN | DONE
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    done_at               TIMESTAMPTZ,
    done_by               VARCHAR(64)
);

-- The hub console reads the open queue for its city.
CREATE INDEX idx_rto_action_open ON rto_action (return_hub_city) WHERE status = 'OPEN';
