# M5 — Entity Relationship Diagram

> Generated from [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) §14 (Data Model).  
> Update this file whenever the schema changes.

```mermaid
erDiagram
    DISPATCH_QUEUE {
        uuid id PK
        uuid da_id
        uuid city_id
        uuid shipment_id
        varchar task_type
        float8 pickup_lat
        float8 pickup_lon
        uuid tile_id
        int queue_position
        varchar status
        varchar payment_mode
        boolean cross_territory
        uuid home_tile_id
        boolean cron_safe
        timestamptz assigned_at
        timestamptz expected_eta
        timestamptz started_at
        timestamptz completed_at
        date operating_date
    }

    DA_CRON_ASSIGNMENT {
        uuid id PK
        uuid da_id
        uuid city_id
        date operating_date
        uuid cron_vertex_id
        float8 meeting_lat
        float8 meeting_lon
        timestamptz scheduled_meeting_time
        uuid van_id
        varchar status
        timestamptz handoff_completed_at
        int parcel_count_handed
    }

    DA_STATUS {
        uuid id PK
        uuid da_id UK
        uuid city_id
        date shift_date
        varchar shift_type
        varchar status
        float8 last_gps_lat
        float8 last_gps_lon
        uuid current_tile_id
        int queue_depth
        timestamptz last_heartbeat
        timestamptz updated_at
    }

    DEFERRED_DISPATCH {
        uuid id PK
        uuid city_id
        uuid shipment_id
        varchar task_type
        uuid tile_id
        float8 pickup_lat
        float8 pickup_lon
        varchar defer_reason
        timestamptz deferred_at
        timestamptz retry_after
        varchar status
        timestamptz assigned_at
        timestamptz escalated_at
        date operating_date
    }

    DA_ASSIGNMENT_AUDIT {
        uuid id PK
        uuid shipment_id
        varchar task_type
        uuid city_id
        uuid tile_id
        uuid da_id_selected
        varchar decision
        int insertion_pos
        int cheapest_insert_extra_sec
        int cron_slack_sec
        boolean used_osrm
        timestamptz decided_at
    }

    DA_STATUS ||--o{ DISPATCH_QUEUE : "holds tasks for"
    DA_STATUS ||--o| DA_CRON_ASSIGNMENT : "has cron schedule"
    DISPATCH_QUEUE ||--o{ DA_ASSIGNMENT_AUDIT : "audited by"
    DEFERRED_DISPATCH }o--|| DISPATCH_QUEUE : "promoted to on retry"
```

## Notes

- `DISPATCH_QUEUE` is append-only — rows are never deleted (XC-D-002). Status transitions are recorded via `status` column updates but row history is preserved via `DA_ASSIGNMENT_AUDIT`.
- `DA_STATUS` has one row per DA (unique constraint on `da_id`). It is the only mutable table in M5 — updated on every GPS heartbeat flush and status change. The authoritative live state is in-memory; DB is flushed every 2 minutes.
- `DA_CRON_ASSIGNMENT` has a unique constraint on `(da_id, operating_date)` — one cron schedule per DA per shift day. Populated at nightly replan by consuming M6's `cron.scheduled` Kafka event.
- `DEFERRED_DISPATCH` rows progress from `PENDING → ASSIGNED` (when a DA becomes available and the order is retried successfully) or `ESCALATED` (after `DeferredRetryJob` determines the order cannot be assigned before shift end) or `EXPIRED` (shift ended, passed to M11).
- `DA_ASSIGNMENT_AUDIT` is fully append-only. Every assignment attempt — successful or deferred — creates one row. `da_id_selected` is `NULL` when `decision` is `DEFERRED_*`.
- `home_tile_id` in `DISPATCH_QUEUE` is non-null only when `cross_territory = true`; it records the tile the order was originally routed to before cross-territory search found a better DA.
- Cross-module references (no DB foreign keys enforced):
  - `da_id` → M1 `users` table (auth module)
  - `shipment_id` → M4 `shipments` table
  - `tile_id`, `cron_vertex_id`, `current_tile_id`, `home_tile_id` → M3 `tile` / `grid_vertex` tables
  - `van_id` → M6 routing tables
