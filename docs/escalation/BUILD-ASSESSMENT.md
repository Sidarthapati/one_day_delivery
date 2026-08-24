# Honest assessment — PRs #129–142 (ops-analytics + M11 exceptions)

_Reviewed 2026-08-24 against `main` @ `28bb824`. Build compiles clean (JDK 21); all module
tests green. Findings verified by reading the code, not the test names — because the tests are
mock-heavy and, as shown below, missed two real runtime bugs._

## One-paragraph verdict

Your friend built **~11 merged PRs, ~3,300 lines, entirely backend** — and built them almost
exactly against the gap analysis: M11 exceptions, ageing report, dispatch execution metrics, DA
scorecards, CSV export, shipment timeline, scan read-model. **The engineering craft is genuinely
good** (clean reason taxonomy, an atomic race-safe denormalization, a real concurrency-index fix,
injection-safe CSV, batched queries with no N+1, clean port-based module boundaries). But it is the
**read / plumbing half** of each capability, validated only by **mock-based unit tests** — which let
**two misleading-in-production defects ship green**, and left the headline feature for your CEO
meeting — an **escalation matrix** — _not actually built_. What exists is exception **case capture**,
not **escalation**.

## What is real and works today
- **M11 delivery/pickup-failure → case → resolve → RTO/reschedule loop.** A DA marking a task failed
  (`DaTaskServiceImpl.markFailed` → `emitDropFailed/emitPickupFailed`) genuinely opens a case, counts
  attempts, and resolving it emits events that drive the M4 state machine. End-to-end, tested at unit level.
- **Reason taxonomy** — 10 real reason codes with free-text normalization (`ExceptionReason.fromCode`).
- **Dispatch metrics that read live columns:** attempt-success %, stops/hr pace, pending, 7-day DA
  history, GPS trail. `dispatch_queue` lifecycle timestamps _are_ written by the live DA app path.
- **SLA colour link (#142)** — `sla_shipment` is populated by live M10, so per-task RAG status is real.
- **Scan read-model (#132)** — atomic, idempotent, newer-only last-scan denormalization. Clean.
- **CSV export (#139)** — CSV-injection-safe, stable snapshot paging, 50k cap.
- **Shipment timeline (#141)** — correct stable merge of state-history + scan-trail across a clean port.

## What is _in place but I'd be skeptical of_ (would embarrass in a live demo)
1. **`on-time %` on DA scorecards is a phantom — it is always ~0.0.** The SQL requires
   `expected_eta`, but `expected_eta` is **never written on the normal task path** (only a restart-
   recovery edge in `ShiftLoadJob:176`). So every DA reads "0% on-time." It looks authoritative and is
   wrong. (#140)
2. **Ageing shipped broken and was hot-fixed.** #134's native query used `s.state::text`, which
   Hibernate mis-parsed → live **HTTP 500** on `/ageing`. #136 correctly fixed it (`cast(s.state as
   text)`). The fix is fine; the **process** is the worry — mock tests passed, so a broken endpoint
   merged. There is _still_ no integration test guarding it.
3. **Ageing/timeline are under-fed.** They measure dwell from `last_scan_at` / `scan_ledger`, but M8
   isn't producing scans yet, so ageing silently degrades to **age-since-booking** and the timeline is
   mostly state-history. Correct code, not-yet-flowing input. Don't present these as "dwell" numbers.
4. **Control-tower date default is UTC, not IST.** Controllers use bare `LocalDate.now()`; on Render
   (UTC) the board shows the **wrong/empty operating day for the first ~5.5h of every IST day**
   (00:00–05:30 IST). One-line fix (`LocalDate.now(IST)`), but it's a live-ops footgun. (#135/#137/#140)

## What is NOT built (the part that matters for the CEO meeting)
- **No escalation engine of any kind.** No severity/priority, no SLA timers, no auto-escalation to
  SUPERVISOR → STATION_MANAGER → ADMIN. A case can sit OPEN forever; if no human opens the queue,
  nothing happens. M11 is a **queue + manual resolve**, not a matrix.
- **A DA no-show does not open a case.** `AbsentDaDetectionJob` emits `DA_ABSENT`, but M11 has no
  branch for it (falls to a no-op) — and worse, a DA who _never comes online_ stays `OFFLINE` and is
  **never even flagged absent** (the job skips OFFLINE). The signature scenario is unwired _and_
  undetected.
- **The time-critical breaches produce no case.** `CRON_MISSED` and `FLIGHT_MISSED` case types exist
  but have **no emitter** — nothing ever creates them. For a _one-day_ SLA, the cron-cutoff and flight
  legs are exactly the escalations that matter, and they're dead code.
- **No auto-RTO / max-attempt enforcement.** Hitting the attempt cap only _labels_ a case
  UNDELIVERABLE; a human must click RTO. No timer forces it. `da_attributable` (the accountability
  flag) is dead in practice → no DA-penalty path.
- **No notifications.** Consistent with the platform-wide gap — nothing pushes SMS/push/call to a DA,
  supervisor, or customer. Escalation today = someone watching a screen.
- **Zero UI.** All of this is API-only. None of the six consoles gained a screen.

## Smaller things worth a cleanup pass
- **Scan-trail GET has no per-parcel ownership check** — any operational role can pull any parcel's
  trail incl. actor IDs (IDOR-ish; `ScanController` comment admits gating "deferred").
- **CSV is labelled "streaming" but is in-memory** (50k cap). Fine at pilot scale; rename the doc.
- **No integration tests hitting real Postgres** for the native ageing/scorecard queries — the exact
  blind spot that let the `::text` 500 ship.
- **Attribution to scrub (your rule):** commit `c7f9c4b` (#136) carries a `Co-Authored-By: Claude`
  trailer, and a few files (`ScanController`, `AdminOrderSummaryServiceImpl`, `AdminOrderQueryServiceImpl`)
  have persona-tagged design comments. Worth removing before this is shown around.

## Per-PR scorecard
| PR | What | Verdict |
|---|---|---|
| #120 M11 exceptions (foundation) | case+action model, taxonomy, resolve→events | ✅ real, but capture-only |
| #131 M11 increment-1 | consumers, batch resolve, attempt policy, RTO seam | ✅ real; no-show/cron/flight unwired |
| #132 scan-read + last-scan | atomic denormalization + trail endpoint | ✅ functional (IDOR nit) |
| #134 ageing report | dwell-band matrix | 🔴 shipped broken (500) |
| #136 fix ageing cast | `cast(as text)` | ✅ correct fix; process gap |
| #135 dispatch execution metrics | attempt-success, pace | 🟡 functional; avg/hr past-date bug + TZ |
| #137 DA detail | identity, pace, 7-day history, trail | 🟡 functional; ETA-urgency never RED |
| #139 shipments CSV export | injection-safe export | ✅ functional (mislabelled streaming) |
| #140 DA scorecards | stops/hr, on-time, attempt-success | 🟡 on-time % is phantom (0.0) |
| #141 shipment timeline | state-history + scan merge | ✅ functional; under-fed input |
| #142 DA↔SLA parcel link | RAG status on tasks | ✅ functional |

**Bottom line for the meeting:** the friend closed the _plumbing_ gap (exception capture, ops read-
models) with good code, but the **escalation matrix itself does not exist in the system**, and the two
most CEO-relevant scenarios — **DA no-show** and **missing the cron/flight cutoff** — are exactly the
ones that are unwired. Treat the current build as the _data layer_ an escalation system would sit on,
not the escalation system.
