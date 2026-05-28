# M3 — Integration Reference

Module: `grid` | Base path: `/api/grid`, `/api/proposals`

---

## REST Endpoints

### Grid (`/api/grid`)

| Method | Path | Response | Purpose |
|--------|------|----------|---------|
| `GET` | `/{cityCode}/tiles?date=` | `List<TileDetailResponse>` | All active/inactive hexes with demand scores (`demandScoreOrders`, `demandScoreMinutes`). |
| `PATCH` | `/{cityCode}/tiles/{tileId}/active?active=` | 204 | Toggle a hex in/out of service. Requires station manager approval flow in practice. |
| `GET` | `/{cityCode}/tiles/{tileId}/load-score?date=` | `TileLoadScoreResponse` | Real-time intraday load score for a single hex (adjustedLoadScore, severity, unservedOrders). |
| `GET` | `/{cityCode}/vertices` | `List<GridVertexResponse>` | Hex boundary vertices — used to render the grid on a map. |
| `GET` | `/{cityCode}/assignments?date=` | `List<AssignmentResponse>` | Active DA→hex assignments for the city on a given date. |
| `GET` | `/{cityCode}/serviceability?pincode=` | `ServiceabilityResponse` | Resolves a pincode to a hex; returns `serviceable=false` if pincode is unmapped or hex is inactive. |
| `GET` | `/{cityCode}/tile-at?lat=&lon=` | `TileAtResponse` | Maps a lat/lon coordinate to its H3 hex. Used at order creation to attach a hex to a pickup/delivery address. |
| `POST` | `/{cityCode}/replan` | `ProposalResponse` (201) | Manual replan trigger. Body: `{ "date": "...", "daIds": [...] }`. Same path as nightly batch. |
| `POST` | `/admin/init?cityCode=` | 201 | One-time grid initialisation from the city's serviceability YAML. |

### Proposals (`/api/proposals`)

| Method | Path | Response | Purpose |
|--------|------|----------|---------|
| `GET` | `/{proposalId}` | `ProposalResponse` | Fetch a single proposal with its regions. |
| `GET` | `/?cityCode=&date=` | `List<ProposalResponse>` | All proposals for a city on a date (nightly + intraday). |
| `POST` | `/{proposalId}/approve` | 204 | Station manager approves the nightly plan. Activates `DaHexAssignment` rows. |
| `POST` | `/{proposalId}/reject` | 204 | Station manager rejects; notes required. |
| `PUT` | `/{proposalId}/regions/{daId}` | 204 | Edit a DA's hex set inside a `PROPOSED` proposal before approval (Scenario A). |
| `POST` | `/intraday-reassignment` | `IntradayReassignmentResponse` (201) | Move hexes from one DA to another mid-shift (Scenario B). Creates an INTRADAY proposal for approval. |
| `POST` | `/{proposalId}/approve-reassignment` | 204 | Approve the intraday reassignment proposal. |
| `POST` | `/tile-share` | `TileShareResponse` (201) | Request a second DA to share a hex (multi-DA overload case). |
| `POST` | `/{proposalId}/approve-tile-share` | 204 | Approve the tile-share proposal. |

---

## Kafka

### Published by M3

| Topic | Key | Payload | Consumed by |
|-------|-----|---------|-------------|
| `grid.no_da_alert` | `tileId` | `NoDaAlertEvent(cityId, tileId, validDate, reason, alertedAt)` | M5 (Dispatch) — to attempt emergency assignment; M11 (Exceptions) — to open a call-centre ticket. |
| `grid.tile_overload_alert` | `tileId` | `TileOverloadAlertEvent(cityId, tileId, daId, date, severity, expectedOrders, unservedOrders, adjustedLoadScore, sustainedMinutes, alertedAt)` | M5 (Dispatch) — for intraday rebalancing suggestions; M10 (SLA) — for overload-driven SLA degradation. |

### Consumed by M3

| Topic | Group | Payload | Published by |
|-------|-------|---------|-------------|
| `orders.tile_queue_depth` | `grid-service` | `TileQueueDepthEvent(tileId, cityId, date, unservedOrders, bookedOrders, recordedAt)` | M4 (Orders) — emitted whenever unserved order count for a hex changes during a shift. |

> The consumer is disabled (`autoStartup=false`) until M4 starts publishing. Flip `grid.kafka.consumer.auto-startup=true` in `application.yml` to enable.

---

## What M3 Uses from Other Modules

### M1 (Auth) — `DaRosterPort`

`NightlyReplanJob` calls `DaRosterPort.getAvailableDaIds(cityId, date)` to determine how many DAs are rostered per city for the next day. Currently wired to `NoOpDaRosterPort` (returns empty list). When M1 is implemented, annotate the real bean `@Primary` to override.

### M4 (Orders) — direct DB reads

`M4DataLoader` reads M4's `shipment_leg_events` table directly (same Postgres instance, separate schema). Used during nightly demand scoring to compute:

| Query | Output | Used for |
|-------|--------|---------|
| `shipment_leg_events` — pickup timestamps, last 7 days | avg service time per hex (minutes) | `demandScoreMinutes` |
| Consecutive stop pairs, last 7 days | avg inter-stop travel per hex (minutes) | `demandScoreMinutes` |
| `shipment_leg_events` for `shift_date = today` | current order count per hex | 30% weight in demand score |
| `shipment_leg_events` grouped by `shift_date`, last 7 days | historical avg orders per hex | 70% weight in demand score |

All four queries run in independent `REQUIRES_NEW` transactions and degrade gracefully — if the table doesn't exist yet (pre-M4 launch), M3 falls back to bootstrapped demand values.

---

## Who Calls M3

| Module | Endpoint(s) used | When |
|--------|-----------------|------|
| **M4 (Orders)** | `GET /serviceability?pincode=` | At order booking — verify the pickup pincode is in a serviceable hex. |
| **M4 (Orders)** | `GET /tile-at?lat=&lon=` | At order booking — map pickup/delivery coords to a hex ID for leg creation. |
| **M5 (Dispatch)** | `GET /assignments?date=` | At DA assignment time — find which DA owns the pickup hex today. |
| **M5 (Dispatch)** | Kafka `grid.no_da_alert` | Triggers emergency DA sourcing when a hex has no coverage. |
| **M5 (Dispatch)** | Kafka `grid.tile_overload_alert` | Triggers intraday rebalancing suggestions (Level 3 BFS auto-suggest — not yet implemented). |
| **M6 (Routing)** | `GET /tiles?date=` | Nightly route planning — hex centres become graph nodes for van route optimisation. |
| **M6 (Routing)** | `GET /assignments?date=` | Nightly route planning — DA→hex mapping tells the router which hexes each van must cover. |
| **M10 (SLA)** | Kafka `grid.tile_overload_alert` | Flags overloaded hexes for SLA degradation tracking. |
| **M11 (Exceptions)** | Kafka `grid.no_da_alert` | Opens a call-centre queue item when a hex loses DA coverage. |

---

## Scheduled Jobs (internal)

| Job | Schedule (IST) | What it does |
|-----|---------------|-------------|
| `NightlyReplanJob.run` | 01:00 daily | Demand scoring → BFS assignment → `PROPOSED` proposal for each city. |
| `NightlyReplanJob.checkEscalation` | 06:00 daily | Logs `ESCALATION_ALERT` if no proposal is approved yet. |
| `NightlyReplanJob.applyFallbackIfNeeded` | 07:00 daily | Copies yesterday's approved assignments as a `MANUAL` proposal if nothing was approved. |
| `IntradayMonitorJob.run` | Every 5 min, 07:00–20:00 | Polls in-memory load scores; emits `tile_overload_alert` when WARNING/CRITICAL thresholds are sustained. |
| `OsrmMatrixRefreshJob` | Configurable | Refreshes OSRM travel-time matrix for the hex graph (currently bypassed; geometric adjacency is used). |
