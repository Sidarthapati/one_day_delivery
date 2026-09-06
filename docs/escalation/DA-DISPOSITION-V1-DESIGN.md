# DA Disposition v1 — Breaks, Auxiliary & Day-off (Simple Build)

> **Status:** implemented (backend done + tested; driver app + station console alongside). A deliberately
> simpler first slice of `DA-DISPOSITION-DESIGN.md`. The full design's cascade-guard, spare/relief-pool,
> restore op, and GPS-reconciled metrics are **out of scope here** (see the differences table). The
> go/no-go rationale lives in `DA-DISPOSITION-BUSINESS-CASE.md`.

## 1. What this does

A DA raises a **disposition** from the driver app and the system holds his territory instead of the old
all-or-nothing "mark absent → full flood-fill." Three categories, one hold mechanic:

| Category | Approval | Allowance | Reasons |
|---|---|---|---|
| **BREAK** (personal) | **Auto**, within a slot + allowance | **Counts** (60 min/day, splittable) | LUNCH · REST · EV_CHARGING · OTHER — *extensible* |
| **AUXILIARY** (company work) | **Manager-approved** (raise → PENDING → approve/reject) | Doesn't count | COMPANY_WORK · OTHER + note |
| **DAY_OFF** | Raised → PENDING; the manager actions it via the **existing attendance `mark absent → absenceApply`** flow (the disposition `approve` endpoint accepts AUXILIARY only and rejects DAY_OFF), or **Reject** | — | — |

All three, once active, put the DA in **`ON_BREAK`** (territory held, **nothing written to the grid**);
the category + reason ride on the record and show in the app/console. A day-off reuses the existing
absence flow entirely — the manager marks the DA absent, which runs the flood-fill. **Vacate is never
automatic.**

## 2. Break slots (deadline-aware, uniform across meeting modes)

At any time the DA sees the windows in which a break is allowed today. The system reads his cron /
hub-return meeting time(s) from `DaStatusService.getQueue(daId).getCron()`
(`scheduledMeetingTime` + `meetingTimes`). **HUB_RETURN cities reuse the same per-DA channel** (a
`DaCronAssignment` with `vanId == null` and the hub as the vertex), so there is **no per-mode branching**.

> allowed windows = `[shiftStart, shiftEnd]` (from the DA's `Shift`) − each protected window
> `[meeting − dispatch.cron.freeze-minutes(30), meeting]`, keeping only gaps ≥ `min-break-minutes(30)`.

So a 14:00 cron protects 13:30–14:00; breaks are offered before it. A BREAK auto-approve enforces the
slot **hard**; AUXILIARY treats it as advisory (the manager can override company-directed work).

## 3. Allowance

60 min/day, splittable (two 30s, etc.). `remaining = 60 − Σ duration` over the DA's BREAK-category rows
today (ACTIVE/OVERSTAYED/COMPLETED). On **early return** the charged duration is trimmed to actual
elapsed, so returning early refunds the unused minutes. Per-reason `non-counting-reasons` config can make
a reason (e.g. EV_CHARGING) free later without code.

## 4. Overstay → manager (never auto-vacate)

`DispositionMonitorJob` (~60 s) drives it server-side:
- Past `scheduledEnd`, it bumps `escalation_level` (one step per `escalation-step-minutes`), which the
  **driver-app card renders as an escalating "break exceeded — please return" banner** (in-app; the card
  polls). *(The notification is server-driven and surfaced in the app; whether via the polled record
  state or a push is an impl detail — it is not SMS.)*
- Past `scheduledEnd + escalate-after-minutes(30)`, it marks the record **OVERSTAYED** and it appears in
  the **station console** manager view.
- It also **reconcile-closes** a break the DA has effectively left — if his status is no longer
  `ON_BREAK` (the cron freeze took over, or a manager marked him absent), the disposition is COMPLETED.

The manager then decides: from the console they **mark the DA absent** (the existing
`attendance/{daId}/absent` → `absenceApply` flood-fill). The system never vacates on its own.

## 5. Status integration (`ON_BREAK`)

`DaStatusEnum` gains **`ON_BREAK`**. Touch points:
- `AbsentDaDetectionJob.sweep` **skips** `ON_BREAK` — a pocketed phone at lunch must not heartbeat-flip to
  ABSENT and trigger reassignment.
- `CronMonitorJob` deliberately **does not** skip `ON_BREAK` — so an overstay drifting toward the meeting
  still flips `CRON_LOCKED` (**cron wins over a break**; the monitor then closes the break).
- `AbsenceReassignmentServiceImpl.ON_SHIFT` **includes** `ON_BREAK` — so an overstaying DA can still be
  marked absent + reassigned.

## 6. Backend (dispatch only — no `common` / `exceptions` change in v1)

- **Migration `V5_21__create_da_disposition.sql`** — table `da_disposition` (mirrors `da_absence_event`
  conventions; partial unique index = one live disposition per DA). **Note:** this is V5_**21**, not 20 —
  the local/unmerged `feat/da-location-stub` branch already holds `V5_20` (`da_location_stub`); using 21
  avoids the version collision.
- **Domain** `DaDisposition` + enums `DispositionCategory` / `DispositionReason` / `DispositionStatus`.
- **`DaDispositionRepository`**, **`DaDispositionService`/`Impl`** (a `Clock` is injectable for tests):
  `slots` · `request` (BREAK auto-approves + `ON_BREAK`; AUXILIARY/DAY_OFF → PENDING) · `end` ·
  `approve` (auxiliary) · `reject` · `managerView` · `sweep`.
- **`DaDispositionController`** —
  DA: `GET /dispatch/da/{daId}/disposition/slots`, `POST …/disposition/request`, `POST …/disposition/end`;
  Manager: `GET /dispatch/disposition/pending`, `POST /dispatch/disposition/{id}/approve`,
  `POST /dispatch/disposition/{id}/reject` (STATION_MANAGER, city-scoped like `AttendanceController`).
- **`DispositionMonitorJob`** (`dispatch.disposition.sweep-seconds`, default 60).
- **`DispatchProperties.Disposition`** — `daily-allowance-minutes(60)`, `min-break-minutes(30)`,
  `escalate-after-minutes(30)`, `escalation-step-minutes(5)`, `max-escalation-level(5)`,
  `sweep-seconds(60)`, `non-counting-reasons([])`.
- **Tests:** `DaDispositionServiceImplTest` (10) — slots exclude the pre-cron window, break auto-approve /
  allowance / slot refusal / concurrent-guard / splittable, auxiliary PENDING + approve, early-return
  refund, overstay + reconcile-close. Full dispatch suite: **224 green**.

## 7. Driver app (`oneday-driver-app`)

A `DispositionCard` on `WorkScreen`'s `DaWork` (cloning the `AttendanceCard` idiom): shows allowed slots +
remaining allowance, a category/reason picker (Break→lunch/rest/EV; Auxiliary→company work + note; Day
off), a live countdown from the active record, a "pending manager approval" state, the escalating overstay
banner, and "I'm back". API in `src/api.ts`: `getDispositionSlots` / `requestDisposition` /
`endDisposition`.

## 8. Web station console (`oneday-web/apps/station`)

The absence page gains a disposition surface: a **pending-requests** block (approve/reject AUXILIARY &
DAY_OFF) and an **overstay** block that reuses the existing `attendanceAbsent` → `absenceApply`
mark-absent-and-reassign flow. City-scoped via the existing `isAdmin ? cityId : undefined` idiom.
`packages/api`: `dispatch.dispositionPending` / `dispositionApprove` / `dispositionReject`.

## 9. Differences vs the full `DA-DISPOSITION-DESIGN.md`

| Full design | v1 (this) |
|---|---|
| Coverage-gated auto-vacate + notification ladder | **Manager-only vacate**; DA gets in-app overstay banner |
| Per-cluster concurrency budget | dropped (human manager is the guard) |
| Spare / relief-pool · restore grid op | dropped (a held break never reassigns) |
| GPS-reconciled trustworthy metrics | dropped (defer) |
| Unified entity folding heartbeat-absence | standalone `da_disposition`; `da_absence_event` untouched |
| Two availability axes | one `ON_BREAK` status + category/reason on the record |

## 10. Edge cases handled / noted

Handled: overstay-into-cron (CronMonitorJob wins + monitor closes) · double-request (live-per-DA unique
index + guard) · early-return refund · marked-absent-while-ON_BREAK (`ON_SHIFT` includes `ON_BREAK`;
monitor closes) · IST zone throughout. Noted for later: open-ended auxiliary (no scheduled end → manager
tracks) · a break still ACTIVE at shift end (auto-closed by the monitor when the DA drops OFFLINE) ·
wiring `BREAK_OVERSTAYED` to an M11 event/alert (trivial follow-up; v1 surfaces overstays via the
dispatch manager-view endpoint).
