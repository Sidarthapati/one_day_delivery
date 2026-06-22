# OneDay Demo — End-to-End Coverage Checklist (M1 → M6)

Status of what the **unified dashboard** (localhost:5173) demonstrates, per module, with what's left.
Legend: ✅ demoable in UI · ⚠️ partial / indirect · ❌ built server-side but no UI path · ⛔ not built.

---

## M5 (Dispatch) — PR-by-PR UI coverage

| PR | Capability | UI status | Notes / what's left |
|----|-----------|:---------:|---------------------|
| #1 | event vocabulary | ⚠️ | `DA_ABSENT`/`SHIFT_ENDED`/`QUEUE_REORDERED` fire from demo actions (not shown as events) |
| #2–3 | tables + entities/repos | ✅ | queues/deferrals/cron persist & render |
| #4 | shift load | ✅ | "Load shift" — seats DAs on **real M6 cron + van** |
| #5 | watchdogs (cron-lock / absent / shift-end) | ✅ | "absent" button, "End shift" button; CRON_LOCKED shows near a meeting |
| #6 | cron feasibility | ✅ | `✓ cron` per task + `CRON_INFEASIBLE` deferrals |
| #7 | assigner (least-loaded, cheapest-insertion, defer) | ✅ | Assign pickups/deliveries · **cross-territory ❌** (no-op adjacency — can't fire) |
| #8 | hear bookings (M4 → assign) | ⚠️ | real RabbitMQ consumer live; demo also synthesizes. A real booking in the M4 console *can* flow in, but there's no breadcrumb showing it |
| #9 | hear cancels | ✅ | per-task ✕ cancel + real consumer |
| #10 | DA action endpoints | ⚠️ | "Work next" drives en-route / van-handoff / drop-collected / drop-completed. **`gps`, `failed` ❌** |
| #11 | OTP pickup confirmation | ❌ | `verify-otp`/`resend-otp` endpoints exist; **no UI** |
| #12 | station-manager board | ⚠️ | the Dispatch tab *is* a per-DA board (queues, slack, deferrals); the **per-tile `GET /dispatch/tiles/{tile}/queue` ❌ unwired** |
| #13 | autonomous jobs | ⚠️ | "Retry deferred" = manual job pass; **tile-queue-depth → M3 publish ❌ not visible** |
| #14 | circuit breakers + DLQ replay | ❌ | breakers run server-side; **DLQ replay endpoint unwired** |
| #15 | metrics + health | ❌ | Micrometer **counters only — no `/metrics` endpoint or dashboard** |

### What's remaining on the UI for M5
1. **OTP pickup (PR #11)** — a "verify OTP" step on a task → `PICKUP_COMPLETED`.
2. **Cross-territory (PR #7)** — provide a demo `AdjacentDaProvider` + enable the flag + engineer a load imbalance so the `XT` spill actually fires.
3. **Station per-tile view (PR #12)** — wire `GET /dispatch/tiles/{tile}/queue` to a tile click.
4. **Tile-queue-depth feed (PR #13)** — surface "tile backlog → M3" (e.g., a small per-tile heat overlay or counter).
5. **DA actions (PR #10)** — explicit `failed` + GPS-ping controls (today only via Work next).
6. **Ops vitals (PR #14–15)** — DLQ replay button + a metrics/health strip (assignment latency, queue depth, OTP success, absent count).
7. **Booking→dispatch breadcrumb (PR #8)** — flag queue items that came from a real M4 booking vs synthetic "Assign N".

---

## End-to-end checklist — M1 → M6

### M1 · Auth/Identity — **Booking & Ops** world
- [x] Login / create account (JWT + role)
- [x] Create users (DA, station manager, …)
- [x] Roles / Permissions (RBAC)
- [x] B2B onboarding (account + credit)
- [x] Role-gated actions (customer can book; admin can't, etc.)

### M2 · Pricing — **Book Shipment**
- [x] Quote: volumetric weight, city-pair, B2C/C2C/B2B, COD, GST
- [ ] Cost-floor / rate-card admin view (ADMIN endpoint exists; not surfaced)

### M3 · Grid — **Planning** tab
- [x] Seed demand → demand heatmap
- [x] Generate territories (M3 replan + approve), DA-territory map, coverage/understaffed
- [x] Serviceability check (used by booking pins)
- [x] DA breakdown (workload vs target / utilisation)
- [ ] Live demand feedback from M5's tile-queue-depth (consumer exists; not visualised)

### M4 · Orders — **Booking & Ops** world
- [x] B2C / C2C / B2B booking (map pins → serviceable → quote → pay)
- [x] Payment: Prepaid (mock Razorpay), Book-on-Credit (B2B), COD
- [x] Cart + bulk destination upload
- [x] Cancellation (+ refund path)
- [x] Admin shipments / recent bookings
- [ ] Live shipment **state-machine** view (BOOKED→…→DELIVERED) per shipment

### M5 · Dispatch — **Dispatch** tab
- [x] Load shift (real M6 cron + van)
- [x] Assign pickups + deliveries (least-loaded, cheapest-insertion)
- [x] Cron-meeting feasibility (`✓ cron`) + deferrals with reasons
- [x] Work next (task lifecycle: en-route → van-handoff / drop-collected → drop-completed + COD)
- [x] Retry deferred · Cancel task · Mark absent · End shift
- [x] Rich per-DA card (grid, territory, van, cron clock + all meetings, vertex, distance, ping, P/D split, done/COD/handed counts, load bar)
- [ ] OTP confirmation · cross-territory firing · station per-tile view · tile-depth feed · DLQ replay · metrics/health (see "remaining" above)

### M6 · Routing — **Planning** + **Execution** tabs
- [x] Generate van routes (VRP), loops, fleet provisioning (under-provisioned), covered/deferred meeting vertices
- [x] Run the day: vans animate, GPS, arrival, running-late
- [x] DA↔van handoff on the map (DA travels home→pickups→vertex→deliveries→home; live remaining counts; click-to-zoom)
- [x] Custody/handoff scans in the RabbitMQ feed; collects sourced from M5's real queue
- [ ] Van breakdown / recovery (`POST /routing/vans/{id}/recovery` exists; not surfaced)

### Cross-module integration
- [x] M3 territories → M5 shift roster
- [x] M6 cron schedule → M5 (DAs seated on real vans, read directly via port)
- [x] M5 pickup queue → M6 run (collects = real M5 parcels)
- [x] One dashboard spanning M1–M6 (world switch + embedded console)
- [ ] Live trace of **one** parcel across all legs (book → assign → carry) with a single id breadcrumb
- ⛔ M7 (hub), M8 (barcode), M9 (airline), M10 (SLA), M11 (exceptions) — **not built**, so the chain visibly stops at the hub/flight boundary

---

## Suggested priority to "finish" the demo
1. **OTP pickup (M5 #11)** + **booking→dispatch breadcrumb (#8)** — completes the human + integration story.
2. **Cross-territory firing (#7)** — the one headline M5 feature that currently can't be shown.
3. **Station per-tile view (#12)** + **tile-depth overlay (#13)** — the supervisor + M5→M3 loop.
4. **One-parcel trace** across M1→M6 — the single most compelling "real picture" view.
5. **Ops vitals (#14–15)** — DLQ replay + metrics strip (lowest demo value, highest "production-ready" signal).
