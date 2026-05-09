# M3 — DA Territory Assignment Algorithm: Discussion & Resolution

| Field | Value |
|-------|-------|
| Status | **Resolved** — decisions incorporated into M3-GRID-DESIGN.md v1.0 |
| Relates to | M3-GRID-DESIGN.md §5.4 |

All three proposed components are adopted from day one. This document preserves the decision rationale.

---

## Original BFS approach — weaknesses (retained for reference)

1. **Adjacency is geometric, not physical** — tiles adjacent by index may be separated by rivers, highways, or railway lines.
2. **Demand scored in order count, not DA time** — real constraint is shift hours, not parcels. Dense apartments and scattered bungalows with equal order counts take very different amounts of DA time.
3. **Greedy local decisions, globally suboptimal** — BFS never backtracks; it picks the locally best next tile without considering the full partition simultaneously.

---

## Decisions made

### Decision 1 — Skip the staged rollout; implement all three components from day one

**Why the staged rollout was originally proposed:** risk reduction during early operations.

**Why we skipped it:** The `AssignmentService` interface is already designed as swappable, so the rest of the system (proposal workflow, approval UI, DB schema) is unaffected by what solver sits behind it. Starting with all three components avoids the cost of a mid-flight algorithm migration.

**Acknowledged constraint:** Time-based demand scoring (Component 2) bootstraps with `pickup_duration = 12 min/order` (flat default) until M4 GPS data accumulates. The solver runs on real time-data as soon as 20 completed pickups exist per tile. See M3-GRID-DESIGN.md §5.6.

### Decision 2 — Contiguity constraint: lazy cuts approach

The open question ("flow-based vs lazy cuts") is resolved in favour of **lazy cuts**:

```
Solve CP-SAT with load-balance constraints only.
For each round:
    For each territory k: BFS to find connected components.
    If any territory is disconnected: add cut constraint blocking that disconnected sub-assignment.
    Re-solve.
Until all territories are connected.
```

**Why lazy cuts over flow-based spanning tree:**
- Flow-based requires O(K × N) additional variables and constraints upfront, significantly increasing the model size for a problem that is usually contiguous after the first load-balance solve.
- Lazy cuts adds constraints only where violations actually occur. In practice, most territories are already connected after the initial solve (BFS grows naturally from high-demand seeds via the objective). Cuts are needed for 5–15% of territories in typical runs.
- Convergence at our scale (≤200 tiles, ≤70 DAs): 2–5 rounds, under 30 seconds total. Empirically validated on similar logistics partitioning problems.

### Decision 3 — OSRM adjacency threshold: 600 seconds (10 minutes) default

Calibrate per city once real DA GPS tracks are available. Mumbai and Delhi likely need 480–540 s due to traffic density.

### Decision 4 — BFS retained as fallback only

BFS `BfsAssignmentServiceImpl` is retained behind the same `AssignmentService` interface. It activates automatically on CP-SAT timeout (>60 s) or OR-Tools unavailability. The proposal always records `solver_type = BFS_FALLBACK` so station managers know a fallback was used.

---

## Decision 5 — Intraday reactivity: Level 2 committed, Level 3 optional

The nightly assignment model is deliberately stable. Two intraday layers are added on top:

**Level 2 (committed):** `IntradayMonitorJob` runs every 5 minutes during shift hours. Consumes `dispatch.tile_queue_depth` from M5 (published every 5 min). Computes `adjusted_load_score = unserved_orders / expected_by_now` per tile with hysteresis (15-min sustained threshold for WARNING, 10-min for CRITICAL). Emits `grid.tile_overload_alert` to Kafka. Station manager receives push notification + deep-link to manual override. No automated territory changes.

**Level 3 (optional, good-to-have):** On `CRITICAL` alert, run a local BFS suggestion — find an adjacent DA with spare capacity, check contiguity for both affected territories, surface as an inline approve/reject action on the notification. Build only after Level 2 usage patterns are validated.

## Remaining open items (carried to implementation)

- OSRM `ADJACENCY_THRESHOLD_SECONDS` per-city calibration (G5 in design doc)
- CP-SAT `LOAD_TOLERANCE` calibration from first 2 weeks of ops data (G6)
- Bootstrap `pickup_duration_default` per-city calibration before go-live (G7)
- Intraday overload threshold calibration: 1.5× and 2.0× `adjusted_load_score` are starting points; actual thresholds should be calibrated from the first few weeks of ops data to minimise false alerts
- Proposal diff UI: what does the station manager see when territories change significantly night-over-night? (design task, not algorithm)
- Multi-DA tile handling edge case: what if a tile's demand is so high that even after Component C pre-processing the solver cannot balance it within tolerance? (→ E11 in design doc)
- `dispatch.tile_queue_depth` Kafka contract with M5: exact payload schema and 5-minute cadence need to be agreed cross-module before M5 implementation begins
