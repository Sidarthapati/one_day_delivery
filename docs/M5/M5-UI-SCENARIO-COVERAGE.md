# M5 — Scenario Coverage in the Demo UI

Derived from `docs/M5/M5-Implementation-Plan.md` (15 PRs / 8 phases). For each M5 capability:
the scenario to demo, and whether you can **see it in the UI** today.

- **UI surface for M5 = the Dispatch tab** (`/api/demo/dispatch/*`: load-shift · assign · work-next ·
  retry-deferred · state · reset). The Execution tab is M6.
- Legend: ✅ visible & exercisable · ⚠️ partial / indirect · ❌ built in M5 but no UI path.

---

## 1. Shift infrastructure (Phase 2 · PRs 4–5)

| # | Scenario | In UI? | Where / why not |
|---|----------|:------:|-----------------|
| S1 | **Shift load** — DAs come on shift from M3 territories + M6 cron | ✅ | "Load shift" → one DA card per DA, each showing its cron meeting (`cron Xm`). |
| S2 | **DA status states** (OFFLINE→IDLE→IN_PROGRESS→CRON_LOCKED→AT_CRON→ABSENT) | ✅ | Badge shows **IDLE**, **IN_PROGRESS** (Work next), **ABSENT** (per-card "absent" button), **OFFLINE** (End shift), **CRON_LOCKED** (meeting within freeze window). Only **AT_CRON** is still unreachable (needs a 200 m GPS proximity event). |
| S3 | **GPS heartbeat** (`updateGps`, dirty-flush) | ❌ | DA positions are set once at load; there's no UI to stream GPS pings. |
| S4 | **Absent-DA detection** (heartbeat timeout → ABSENT + `DA_ABSENT`) | ✅ | Per-DA **"absent"** button forces ABSENT + emits `DA_ABSENT`; the DA stops being assignable so new pickups in its tiles defer. (The heartbeat-timeout *job* itself still isn't time-triggered, but the state + effect are demonstrable.) |
| S5 | **Shift end** (defer QUEUED, set all OFFLINE) | ✅ | **"End shift"** button → every QUEUED task → `SHIFT_ENDED` deferral; every DA → OFFLINE. |

## 2. Core assignment engine (Phase 3 · PRs 6–7) — the heart, best covered

| # | Scenario | In UI? | Where / why not |
|---|----------|:------:|-----------------|
| S6 | **Pickup assignment happy path** — least-loaded DA + cheapest-insertion | ✅ | "Assign N pickups" → queues fill; summary shows placed vs deferred. |
| S7 | **Cron-meeting feasibility** (the hard constraint) | ✅ | `✓ cron` per queued task; infeasible pickups land in **Deferred** as `CRON_INFEASIBLE`. |
| S8 | **Cheapest-insertion ordering** | ⚠️ | Queue **positions** are visible; the extra-travel math that chose the slot isn't surfaced. |
| S9 | **Cross-territory dispatch** (spill to adjacent DA) | ❌ | The `XT` badge exists, **but it can never light up**: `AdjacentDaProvider` is a no-op until M3 exposes tile adjacency, so the spill branch is never entered. |
| S10 | **Deferral reasons** (NO_DA / CRON_INFEASIBLE / CRON_LOCKED / DA_ABSENT / SHIFT_ENDED) | ⚠️ | **NO_DA_AVAILABLE** + **CRON_INFEASIBLE** appear readily; **CRON_LOCKED** only with an imminent meeting; **DA_ABSENT / SHIFT_ENDED** never (their triggers aren't reachable). |
| S11 | **Deferred retry** | ✅ | "Retry deferred" → re-runs assignment; some clear as load redistributes. |
| S12 | **Post-cron retry** (skip cron once meeting COMPLETED) | ❌ | No UI path to mark a cron meeting COMPLETED. |
| S13 | **Cancel task** (QUEUED→CANCELLED; IN_PROGRESS→error) | ✅ | Hover a **QUEUED** task row → **✕** cancels it (drops from queue, resequences, emits `QUEUE_REORDERED`). |

## 3. Event consumers (Phase 4 · PRs 8–9)

| # | Scenario | In UI? | Where / why not |
|---|----------|:------:|-----------------|
| S14 | **ShipmentCreated → assignPickup** | ⚠️ | The assignment **engine** is what you see, but the demo calls `assignPickup` directly with synthetic pickups — it does **not** flow a real `ShipmentCreatedEvent` through the RabbitMQ consumer. |
| S15 | **assignDelivery** (the delivery side) | ✅ | **"Assign N deliveries"** synthesizes inbound parcels → `DispatchService.assignDelivery`; `DELIVERY` tasks (badge **D**) appear in DA queues alongside pickups (**P**). *(Driven directly, not via the real `ShipmentStateChanged` consumer.)* |
| S16 | **ShipmentCancelled → cancelTask** | ❌ | No UI path. |
| S17 | **DaCronScheduled consumer** (M6 cron → `da_cron_assignment`) | ✅ | Indirect: the `cron Xm` time + the van each DA meets come from M6's real cron events (once an M6 plan is approved). |

## 4. DA-facing API (Phase 5 · PRs 10–11)

| # | Scenario | In UI? | Where / why not |
|---|----------|:------:|-----------------|
| S18 | **Task lifecycle** (gps / en-route / van-handoff / failed / drop-collected / drop-completed / COD) | ✅ | "Work next" now drives **both** legs: PICKUP → en-route → van-handoff (COMPLETED); DELIVERY → drop-collected → drop-completed (with COD on ~half). `gps` and explicit `failed` are still not surfaced. |
| S19 | **OTP verify / resend** (pickup confirmation) | ❌ | No OTP UI in the Dispatch tab. |

## 5. Station manager view (Phase 6 · PR 12)

| # | Scenario | In UI? | Where / why not |
|---|----------|:------:|-----------------|
| S20 | **Tile dispatch queue view** (city-scoped) | ❌ | `StationDispatchController` (`GET /dispatch/tiles/{tile}/queue`) is built but **not referenced by the demo UI**. |

## 6. Jobs & event publisher (Phase 7 · PR 13)

| # | Scenario | In UI? | Where / why not |
|---|----------|:------:|-----------------|
| S21 | **TileQueueDepthPublisher** (M5 → M3 demand feedback) | ❌ | Background `@Scheduled` job; nothing visual. |
| S22 | **DeferredRetryJob** (auto-retry + escalate) | ⚠️ | "Retry deferred" is the manual equivalent of one job pass; auto-escalation after 3 tries isn't shown. |

## 7. Resilience & observability (Phase 8 · PRs 14–15)

| # | Scenario | In UI? | Where / why not |
|---|----------|:------:|-----------------|
| S23 | Circuit breakers · DLQ replay · Micrometer metrics · health | ❌ | Operational concerns; not surfaced in the demo UI. |

---

## Summary (after the gap-closing pass)

**Now covered in the Dispatch tab:** shift load (S1), DA status states incl. ABSENT/OFFLINE (S2),
pickup assignment (S6), cron-meeting feasibility (S7), cheapest-insertion queues (S8), deferral with
reasons (S10), deferred retry (S11), **cancel task (S13)**, real M6→M5 cron link (S17), **full task
lifecycle for both pickup & delivery (S18)**, **force-absent (S4)**, **end-shift → SHIFT_ENDED + OFFLINE
(S5)**, and the **delivery side (S15)**.

**Still gaps (deferred, with reasons):**
- **Cross-territory (S9)** — needs `dispatch.cross-territory.enabled=true` **and** an engineered load
  imbalance (origin overloaded + neighbour sparse + primary cron-infeasible). The `XT` badge renders
  but won't light up under random demo demand. Demoing it reliably means a scripted overload scenario.
- **OTP pickup (S19)** — needs M4's pickup-OTP flow wired into a task step.
- **Station-manager tile view (S20)** — `GET /dispatch/tiles/{tile}/queue` exists; surfacing it wants a
  tile-click, which lives more naturally on a map (Planning/Execution) than the card-based Dispatch tab.
- **GPS heartbeat (S3)**, **post-cron retry (S12)**, **tile-queue-depth publish (S21)**, **resilience/
  metrics (S23)** — background/ops concerns with no natural demo control.

### What the gap-closers added (this pass)
- `assign-deliveries`, `cancel-task`, `mark-absent`, `end-shift` demo endpoints (all call **real** M5
  services: `assignDelivery`, `cancelTask`, `updateStatus(ABSENT)`+`DA_ABSENT`, shift-end deferral).
- `work-next` now drives the **delivery** lifecycle too (drop-collected → drop-completed, COD on ~half).
- UI: "Assign N deliveries" button, P/D task badges, hover-✕ to cancel a queued task, per-DA "absent"
  button, "End shift" button.
