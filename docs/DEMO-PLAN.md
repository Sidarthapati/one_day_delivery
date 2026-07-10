# M4 → M5 → M6 End-to-End Demo Plan (Benchmark)

**Purpose.** A single reference a human — or Claude — can replay top-to-bottom to decide *"is the
first-mile flow actually working?"* Every step lists the **action**, the **expected M4 state**, the
**expected M5/M6 effect**, and a **verification checkpoint** (UI, DB, or RabbitMQ). If every
checkpoint passes, the M4→M5→M6 slice is healthy. If one fails, its row tells you which module owns
the break.

> Scope: **first-mile happy path + the M5 stress scenarios (overflow / absent / deferral)**. The air
> leg (M9) and hub sortation (M7) are **not built** — the demo fast-forwards across them (see
> §7). This plan does **not** benchmark M7/M9.

---

## 0. What this flow is

```
Customer books (M4)  →  M5 assigns a DA + checks cron feasibility  →  DA verifies pickup OTP
   →  M6 "Run the day": van drives its route, collects from each DA at the meeting point
   →  end-of-run: parcel handed to pickup van  →  Complete first-mile: van returns to hub
   →  [M7 hub + M9 flight — FAST-FORWARDED]  →  last-mile drops (M6 van → DA → customer OTP)
```

Two UIs:
- **`:5173`** — the React ops demo (`demo-ui/`), tabs **Execution** (M6 map + Run the day + Ops
  tracker), **Dispatch** (M5 control tower), **DA Phone**.
- **`:8080`** — the customer view (`Your Bookings`, handover codes).

**Messaging is 100% RabbitMQ (CloudAMQP).** "kafka" in package names is historical only.

---

## 1. Preconditions (must all be true before demoing)

| # | Check | How | Pass |
|---|---|---|---|
| P1 | JDK 21 active | `java -version` | `21.x` (NOT 25 — enforcer rejects) |
| P2 | `.env` present & sourced | `set -a; source .env; set +a` | `SPRING_DATASOURCE_URL`, `CLOUDAMQP_URL`, `ROUTING_OSRM_BASEURL` all set |
| P3 | OSRM `/table` reachable | `curl -s "$ROUTING_OSRM_BASEURL/table/v1/driving/77.2,28.6;77.3,28.7" \| head -c 60` | JSON, not a connection error. **If down, M6 plan fails → "No plan loaded for today".** Use `https://router.project-osrm.org`. |
| P4 | Dev DB reachable | psql one-liner from CLAUDE.md → `SELECT current_database();` | `oneday_cipv` |
| P5 | RabbitMQ healthy | `rabbitmqadmin -N oneday list queues name consumers messages` | every live consumer queue `consumers=1`; all `*.dlq` `messages=0` |

**Boot both servers from the packaged jar (torn-build-immune):**
```bash
./run-demo.sh --build      # first run of the day, or after ANY backend/static change
./run-demo.sh              # subsequent boots (uses existing jar)
```
- `:8080` up (customer) + `:5173` up (ops).
- **Rule:** never `mvn clean install` while a `spring-boot:run` is live (torn classes → masked 401s
  and silent source reverts). `run-demo.sh` runs the jar precisely to avoid this. Static/UI edits
  need `--build` (the jar serves its own embedded `static/`).

---

## 2. Baseline invariants (the benchmark's core assertions)

These must hold at **every** checkpoint, not just at the end:

- **INV-1 — M4↔M5 uniformity:** every M4 `PICKUP_ASSIGNED` shipment ⇔ exactly one M5 `QUEUED` task
  on a live DA. **Zero** `PICKUP_ASSIGNED` shipments with no live DA (orphans).
- **INV-2 — deferral honesty:** every M5 deferred pickup ⇔ its M4 shipment is `BOOKED` (never stuck
  at `PICKUP_ASSIGNED`).
- **INV-3 — cron feasibility is hard:** no pickup is `QUEUED` on a DA that cannot reach its cron
  meeting before cutoff. Infeasible → `CRON_INFEASIBLE` deferral, M4 stays `BOOKED`.
- **INV-4 — counts live in `summary`:** the Dispatch state's numbers come from
  `state.summary.{das,assigned,deferred,cronLocked}`. `state.das` / `state.deferred` are the raw
  arrays (rendering them directly → `[object Object]`).
- **INV-5 — no synthetic data:** every parcel in the run is a **real booking**. There are no
  synthetic-generate buttons (removed). If the map shows parcels you didn't book, something regressed.
- **INV-6 — DLQ stays empty:** a non-empty `oneday.*.dlq` = a contract break (wrong `__TypeId__` /
  mismatched event). First thing to check when anything looks off.

---

## 3. The RabbitMQ "peek" tap (use throughout)

Non-destructive read of the live event flow — leaves messages for the real consumers:
```bash
rabbitmqadmin -N oneday declare queue name=demo.peek durable=false auto_delete=true
rabbitmqadmin -N oneday declare binding source=oneday.shipments.events destination=demo.peek routing_key='#'
rabbitmqadmin -N oneday declare binding source=oneday.da.events        destination=demo.peek routing_key='#'
rabbitmqadmin -N oneday declare binding source=oneday.cron.events      destination=demo.peek routing_key='#'
# ... trigger an action in the UI ...
rabbitmqadmin -N oneday get queue=demo.peek count=20 ackmode=reject_requeue_true   # peek, don't consume
rabbitmqadmin -N oneday delete queue name=demo.peek
```
Exchanges to watch: `oneday.shipments.events` (M4), `oneday.da.events` (M5↔M4↔M6, unified
`DaLifecycleEvent`; **M6 acts only on `PICKUP_COMPLETED`**), `oneday.cron.events` (M6).
The **Execution tab's live RabbitMQ feed** is a *real* PUBLISH/CONSUME tap (`GET /api/demo/amqp-tap`),
not cosmetic — use it as the primary visual during Run the day.

---

## 4. Happy-path sequence (the benchmark run)

Do these **in order**. Each row: Action → expected M4 state → expected M5/M6 → checkpoint.

### Setup & seed

| Step | Action (UI) | Expected result | Checkpoint |
|---|---|---|---|
| S1 | **① Prepare today's plan** (Execution) | M6 nightly plan solves; DAs get vans + cron schedule. Success toast names **# of DAs at play**. | Toast shows a DA count > 0. If it says "0 on real M6 cron" → OSRM was down during Prepare; rebuild M3+M6 together (not Reset). |
| S2 | **② Load shift** (Execution) | M5 loads the roster; DAs go IDLE/assignable for the operating date. | Dispatch tab shows DAs listed, status IDLE. |
| S3 | **Book real pickups** — either use `:8080` to book, or **🌐 Spread pickups** (Execution) which wraps real BOOKED shipments across the DAs (`count > #DAs` wraps → per-DA overflow). | Each booking → M4 `BOOKED` → M4 emits `ShipmentCreatedEvent`. M5 consumes CREATED, runs cheapest-insertion + cron feasibility. | **Peek `oneday.shipments.events`** → `ShipmentCreatedEvent` per booking. |

### Assignment (M5)

| Step | Action | Expected result | Checkpoint |
|---|---|---|---|
| A1 | (automatic on CREATED) | Feasible pickups → M4 `PICKUP_ASSIGNED`, M5 `QUEUED` on a DA. Infeasible/overflow → M5 `CRON_INFEASIBLE` deferral, M4 stays `BOOKED`. | **INV-1 + INV-2** via DB (§6 queries). Dispatch tab `summary.assigned` + `summary.deferred` reflect the split. |
| A2 | Inspect Dispatch tab | Counts sane, no `[object Object]`. | **INV-4**. |

### Pickup OTP (door handshake)

| Step | Action | Expected result | Checkpoint |
|---|---|---|---|
| O1 | **🔑 Auto-verify pickups** (Execution) — simulates the customer↔DA door OTP | Only pickups **already QUEUED on a DA** flip to verified/`IN_PROGRESS`. Deferred pickups are **untouched**. | Customer `:8080` booking detail shows the pickup handover code greying out after verify. Deferred bookings still show "finding an agent". |

### The run (M6) — "Option 1" split

| Step | Action | Expected result | Checkpoint |
|---|---|---|---|
| R1 | **▶ Run the day** (Execution) | Van drives **outbound only** (hub → DA meeting points), collects **only OTP-verified (IN_PROGRESS)** pickups, **parks at its last productive stop**. At run-end: `PICKED_UP → HANDED_TO_PICKUP_VAN` (`POST /api/demo/da/pickups/to-van`). | Map: vans move along their **distinct-coloured** routes, snap to the polyline, park at the last stop with items. Live feed shows collect + handoff narration. Un-verified pickups are left behind. |
| R2 | Refresh **Your Bookings** (`:8080`) | Verified+collected parcels now read **`HANDED_TO_PICKUP_VAN`**. | Customer status = `HANDED_TO_PICKUP_VAN`. **Peek `oneday.shipments.events`** → `ShipmentStateChangedEvent` to that state. |
| R3 | **🏭 Complete first-mile** (Execution) — the **return leg** | Van drives home from its live telemetry position (`POST /api/demo/run-return-to-hub`); `HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB`. | Map: vans drive back to hub. Bookings read `AT_ORIGIN_HUB`. |

### Fast-forward the air leg + last mile (see §7)

| Step | Action | Expected result | Checkpoint |
|---|---|---|---|
| L1 | **📦 Dispatch drops** (Execution) | Real shipments fast-forwarded hub→van→DA (stands in for M7 + M9). Delivery tasks queued in M5. | Drops appear on the Execution "last-mile" list. |
| L2 | **▶ Run the day** (delivery loop) | Van drives drops to each DA meeting point (`DA_TO_VAN`/`VAN_TO_DA` custody). | Map shows delivery loop. |
| L3 | **🏠 Auto-verify deliveries** — simulates the delivery OTP (OD-8, gates `DROP_COLLECTED → DROPPED`) | Parcels reach `DROPPED` / delivered. | Customer booking reads delivered; delivery handover code greys out. |

**End state:** every happy-path parcel is `DROPPED`/delivered; DLQs empty; no orphans.

---

## 5. M5 stress scenarios (the interesting benchmark)

These prove the dispatch engine, not just the happy path. Run each, then re-assert INV-1/INV-2.

### SC-1 — Overflow → CRON_INFEASIBLE deferral
- **Do:** Spread more pickups than DAs can feasibly serve (`count > #DAs`, or add 10+ after the first
  wave).
- **Expect:** excess pickups defer with `CRON_INFEASIBLE`; those M4 shipments stay `BOOKED`
  (INV-2/INV-3). `summary.deferred` climbs.
- **Fail signal:** a deferred shipment stuck at `PICKUP_ASSIGNED` (orphan) → INV-1 broken.

### SC-2 — DA absent → re-cover
- **Do:** Dispatch tab → **⊘ mark absent** on 1–2 DAs.
- **Expect:** the DA's QUEUED pickups defer with `DA_ABSENT` **and the queue row is deleted** (not
  left `DEFERRED`); `mark absent` also calls `reconcile-m4` → any orphaned `PICKUP_ASSIGNED` (no live
  DA) → **`PICKUP_FAILED`** (customer label "Reassigning — finding a new agent").
- **Checkpoint:** `summary.deferred` rises by the DA's task count; **INV-1 holds** (no orphans —
  reconcile flipped them). Customer view shows "reassigning".
- **Fail signal:** deferred count returns to 0 on its own → the `DeferredRetryJob` interval regressed
  below 3600s and auto-drained the deferrals mid-demo.

### SC-3 — Retry heals
- **Do:** Dispatch tab → **Retry deferred**.
- **Expect:** deferred pickups re-attempt; any that now have a feasible live DA → `QUEUED` again, M4
  `PICKUP_FAILED → PICKUP_ASSIGNED` (legal heal) or `BOOKED → PICKUP_ASSIGNED`.
- **Checkpoint:** deferred count drops for the ones that placed; INV-1/INV-2 re-hold.

### SC-4 — Cancellation drops from queue
- **Do:** cancel a booking (`DELETE /api/v1/b2c/shipments/{ref}`) that is still pre-pickup.
- **Expect:** M4 emits `ShipmentCancelledEvent`; M5 removes it from the DA queue (terminates even an
  `IN_PROGRESS` task — a cancelled-after-pickup parcel must NOT keep inflating DA cron load).
- **Fail signal:** cancelled parcels still counted in a DA's cron load → real bookings wrongly defer
  `CRON_INFEASIBLE`.

---

## 6. DB verification queries (source of truth)

Source `.env`, connect to the dev DB, then:

```sql
-- INV-1: PICKUP_ASSIGNED shipments must each map to a live QUEUED task (orphans = 0).
SELECT s.state, count(*)
FROM shipment s
WHERE s.created_at::date = CURRENT_DATE
GROUP BY s.state ORDER BY 2 DESC;

-- M5 queue by status.
SELECT status, task_type, count(*) FROM dispatch_queue
WHERE operating_date = CURRENT_DATE GROUP BY 1,2;

-- INV-2: deferrals by reason, and confirm their shipments are BOOKED.
SELECT defer_reason, status, count(*) FROM deferred_dispatch
WHERE operating_date = CURRENT_DATE GROUP BY 1,2;

-- Orphan hunt (INV-1): assigned in M4 but no active DA task. Expect 0 rows.
SELECT s.id, s.state
FROM shipment s
WHERE s.state = 'PICKUP_ASSIGNED'
  AND NOT EXISTS (
    SELECT 1 FROM dispatch_queue q
    WHERE q.shipment_id = s.id AND q.status IN ('QUEUED','IN_PROGRESS'));
```
Adjust table/column names to the live schema if they've drifted; the intent is what matters.

---

## 7. What is faked, and why that's OK

The chain `pickup DA → hub → flight → hub → delivery DA` is only built through the **first hub
arrival** (M4 `AT_ORIGIN_HUB`) and again for the **last-mile van→DA→customer** leg. The middle —
**M7 hub sortation** and **M9 flight** — is **not implemented** (both modules are empty shells).

The demo bridges the gap: **📦 Dispatch drops** fast-forwards real shipments from `AT_ORIGIN_HUB`
across the hub+flight legs straight to the delivery van, so the last mile can run on real parcels.

**Consequences for the benchmark:**
- `EtaPort` is stubbed (`StubEtaAdapter`, `!prod`): fixed next-day 14:00 IST intercity. ETAs are
  plausible, not flight-derived.
- The flight cutoff does **not** gate anything (`NoOpFlightCutoffPort` → M6 binds
  "deadline-advisory"). So M5's cron feasibility is anchored to M6's **nominal** meeting time, not a
  real departure. This is a **known gap** (M5-R5 / M9), not a demo bug.
- `oneday.flight.events` stays empty (no producer) — M4's `FlightEventsConsumer` is dormant; DEPARTED/
  LANDED states are reached via the fast-forward, not a real flight event.

Don't fail the benchmark for these — they're documented dependency gaps, not regressions.

---

## 8. Pass / fail scorecard

Copy this and tick it on each demo run:

```
[ ] P1–P5 preconditions green (JDK21, .env, OSRM, DB, Rabbit)
[ ] Prepare reports > 0 DAs on the M6 cron
[ ] Spread pickups → every parcel is a REAL booking (INV-5)
[ ] Feasible pickups: M4 PICKUP_ASSIGNED == M5 QUEUED, orphans = 0 (INV-1)
[ ] Infeasible/overflow: CRON_INFEASIBLE deferral, M4 stays BOOKED (INV-2, INV-3)
[ ] Auto-verify pickups touches only DA-queued pickups, not deferred
[ ] Run the day: vans move on distinct-coloured routes, collect only verified, park at last stop
[ ] Run-end: verified parcels → HANDED_TO_PICKUP_VAN (visible on :8080 refresh)
[ ] Complete first-mile: vans return, parcels → AT_ORIGIN_HUB
[ ] Last mile: drops dispatched, van→DA, delivery OTP → DROPPED/delivered
[ ] SC-2 mark-absent: tasks defer + queue row deleted + reconcile → PICKUP_FAILED, no orphans
[ ] SC-3 retry heals deferrals back to assigned
[ ] Dispatch counts render from summary, never [object Object] (INV-4)
[ ] All oneday.*.dlq empty throughout (INV-6)
```

If every box ticks, the M4→M5→M6 first-mile flow is working as designed.

---

## 9. Fast triage when a box fails

| Symptom | Most likely cause | Fix |
|---|---|---|
| "No plan loaded for today" | OSRM `/table` down during Prepare | point `ROUTING_OSRM_BASEURL` at the public OSRM, rebuild M3+M6 together |
| Refresh-status → 401 / "Something went wrong" | torn build (`mvn clean install` ran against a live `spring-boot:run`) | stop app → `./run-demo.sh --build` → restart |
| DAs show "no van assigned" | M3/M6 DA-id desync (Prepare's M6 half failed) | rebuild M3+M6 together, not Reset |
| Deferred count self-drops to 0 mid-demo | `DeferredRetryJob` interval regressed below 3600s | restore `dispatch.monitor.interval-seconds: 3600` |
| `[object Object]` in Dispatch | reading `state.das`/`state.deferred` (arrays) instead of `state.summary.*` | INV-4 |
| Static/UI change not showing | jar serves embedded static | `./run-demo.sh --build` |
| A `*.dlq` non-empty | event `__TypeId__`/type mismatch (contract break) | inspect the DLQ body; the producer/consumer types disagree |
