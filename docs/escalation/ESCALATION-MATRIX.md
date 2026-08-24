# Godspeed — Operations Escalation Matrix (v1)

_One-day intercity parcel delivery. This defines **who acts, what they do, in what time, and who it
escalates to** when something goes wrong on the delivery chain — and, honestly, **which rows the
system enforces today** vs. which are manual or not yet wired._

Owner: Ops · Audience: CEO review, Station Managers, Field Supervisors, Control Tower.
Companion doc: `BUILD-ASSESSMENT.md` (what's actually built).

---

## 1. Why this matters

Our promise is **delivery within one day** (24h public / 16h internal target). On a one-day clock
there is **no slack to absorb a silent failure** — a DA who doesn't show up, a parcel that misses the
hub cron, a flight that slips. Escalation is not paperwork; it is the mechanism that converts a
detected problem into a **recovery action fast enough to still hit the promise**. The matrix below is
deliberately time-aggressive because the SLA is.

## 2. Severity bands

Severity is defined by **impact on the one-day promise**, not by how the failure feels.

| Band | Meaning | SLA linkage | Default first-touch time |
|------|---------|-------------|--------------------------|
| **P1 — Critical** | Will breach the one-day promise today if not recovered now | SLA `RED` / `BREACHED`, or any cron/flight-cutoff miss | **≤ 10 min** |
| **P2 — At risk** | Buffer is shrinking; recoverable if acted on this shift | SLA `AMBER` | **≤ 30 min** |
| **P3 — Routine** | A failure with buffer remaining (e.g. attempt-1 fail, reattemptable) | SLA `GREEN` | **Same shift** |

## 3. Escalation tiers (roles that exist in the platform)

`DELIVERY_ASSOCIATE` → **`SUPERVISOR`** (field/ops) → **`STATION_MANAGER`** → **`ADMIN`** (regional/ops-
head) — with **`CALL_CENTER_AGENT`** owning all customer-facing communication in parallel. These are the
same three tiers M10 already uses (`EscalationLevel = SUPERVISOR, STATION_MANAGER, ADMIN`).

**Escalation rule of thumb:** if the first responder has not _resolved or actively owned_ the item
within its band's first-touch time, it escalates one tier up, and the clock restarts. P1 items that
survive two tiers go to a same-day war-room (ADMIN).

## 4. Support legend (be honest in the room)
- **✅ Enforced** — the system detects the trigger and creates/routes the item automatically today.
- **🟡 Partial** — detected or capturable, but routing/timers/notification are manual or degraded.
- **🔴 Manual / not wired** — no automatic detection or case; depends on a human noticing (WhatsApp/eyes).

---

## 5. The matrix

### A. First-mile (pickup)

| # | Trigger | How it's detected | First responder → action | Band | Escalates to (if not owned in time) | System support today |
|---|---------|-------------------|--------------------------|------|-------------------------------------|----------------------|
| A1 | **DA no-show — never came online for shift** | Roster (expected) vs. live-online reconciliation | SUPERVISOR reassigns the DA's tiles/pickups to a standby DA; CALL_CENTER holds affected pickups | P1 | STATION_MANAGER (≤10m) → ADMIN | 🔴 **Not wired.** No roster-vs-online check; a never-online DA stays `OFFLINE` and is never flagged. **Highest-priority gap.** |
| A2 | **DA goes silent mid-shift** (GPS heartbeat lapse) | `AbsentDaDetectionJob` (heartbeat > threshold) → `DA_ABSENT` | SUPERVISOR calls DA; if unreachable, reassign open pickups | P1/P2 | STATION_MANAGER | 🟡 **Detected, not actioned.** `DA_ABSENT` fires but M11 doesn't consume it → **no case opens**, no reassignment. |
| A3 | **Pickup failed — sender not ready / unreachable** | DA marks failed → `PICKUP_FAILED` (reason) | Auto-case opens; SUPERVISOR triages: reschedule pickup or cancel | P3→P2 | STATION_MANAGER on 2nd failure | ✅ **Case opens** (M11), attempt counted, `RESCHEDULE_PICKUP` wired. 🟡 no timer, no auto-notify. |
| A4 | **Pickup failed — wrong/incomplete address** | `PICKUP_FAILED` reason `ADDRESS_INCORRECT` | CALL_CENTER contacts sender to correct; SUPERVISOR reschedules | P3 | STATION_MANAGER | ✅ captured; 🟡 correction + notify manual |

### B. Middle-mile (cron cutoff · hub · air)

| # | Trigger | How it's detected | First responder → action | Band | Escalates to | System support today |
|---|---------|-------------------|--------------------------|------|--------------|----------------------|
| B1 | **Cron missed — parcel won't reach hub before airline cutoff** | Should fire `CRON_MISSED` | STATION_MANAGER: expedite to hub, or rebook next flight/lane, or convert to next-day + notify customer | **P1** | ADMIN (same-day) | 🔴 **Dead.** `CRON_MISSED` case type exists but **has no emitter** — nothing detects it. Critical for a 1-day SLA. |
| B2 | **Flight missed / delayed / reassigned** | M9 `FLIGHT_REASSIGNED` (M7 re-bags) | STATION_MANAGER confirms re-bag; CALL_CENTER notifies impacted parcels | P1 | ADMIN | 🟡 M7 **re-bags on reassignment**, but no `FLIGHT_MISSED` **exception case** (unwired), and M9 producer not live. |
| B3 | **Hub overload — inbound surge / stand capacity** | M7 `HUB_OVERLOAD_ALERT` | HUB_OPERATOR + STATION_MANAGER: open overflow, throttle inbound | P2 | ADMIN | 🟡 **Alert fires** (advisory); response is manual, no throttle enforcement. |
| B4 | **Bag/handoff discrepancy** (short/extra/missort) | Inbound reconcile / `HANDOFF_DISCREPANCY` | HUB_OPERATOR reconciles; SUPERVISOR investigates missing parcel | P2 | STATION_MANAGER | 🟡 reconciliation data exists; no auto-case/owner. |

### C. Last-mile (delivery)

| # | Trigger | How it's detected | First responder → action | Band | Escalates to | System support today |
|---|---------|-------------------|--------------------------|------|--------------|----------------------|
| C1 | **Delivery failed — attempt 1** (customer unavailable/refused) | DA marks failed → `DROP_FAILED` (reason) | Auto-case; SUPERVISOR schedules reattempt (same/next day) | P3 | — | ✅ case opens, attempt counted, `RESCHEDULE_DELIVERY` wired. |
| C2 | **Delivery failed — reaches max attempts** | Attempt cap (`maxReattempts`, default 2) | STATION_MANAGER decides: `INITIATE_RTO` or extend | P2 | ADMIN | 🟡 case **labels** UNDELIVERABLE but **RTO is a manual click** — no auto-RTO, no timer. |
| C3 | **RTO in progress** | `RTO_INITIATED` → state machine | SUPERVISOR tracks return to origin; CALL_CENTER informs sender | P2/P3 | STATION_MANAGER | ✅ RTO seam wired end-to-end (M11→M4). 🟡 no attempt-count policy / DA penalty. |
| C4 | **Parcel reported missing / lost** | `MARK_MISSING` on a case | STATION_MANAGER opens loss investigation; CALL_CENTER notifies | P1 | ADMIN | 🟡 case can be marked MISSING; **no investigation/loss-recovery workflow** behind it. |

### D. SLA & cross-cutting

| # | Trigger | How it's detected | First responder → action | Band | Escalates to | System support today |
|---|---------|-------------------|--------------------------|------|--------------|----------------------|
| D1 | **SLA turns AMBER (at risk)** | M10 `sla_shipment` AMBER | SUPERVISOR pulls the parcel forward on its current leg | P2 | STATION_MANAGER | ✅ **detected + escalation event** (M10 `sla_escalation`). 🟡 no notification push. |
| D2 | **SLA turns RED / BREACHED** | M10 RED/BREACHED | STATION_MANAGER owns recovery; ADMIN informed | P1 | ADMIN | ✅ detected + tiered escalation levels exist. 🟡 action manual. |
| D3 | **COD cash variance** (collected ≠ deposited) | Admin COD cash-recon (`cod_cash_deposit`) | STATION_MANAGER confronts DA; hold further COD assignments | P2 | ADMIN | 🟡 reconciliation exists; **variance thresholds/auto-hold not defined**. |
| D4 | **GPS-integrity / spoof suspicion** | (none) | SUPERVISOR audits DA trail; STATION_MANAGER acts | P2 | ADMIN | 🔴 **No spoof detection.** Append-only GPS/scan ledger is the only forensic base. |

---

## 6. How the system supports this today — scorecard

| Capability the matrix needs | Status | Note |
|---|---|---|
| Capture pickup/delivery **failures** as cases | ✅ | M11, real taxonomy + attempt count |
| Resolve → **RTO / reschedule / reassign** drives state machine | ✅ | end-to-end wired |
| **SLA at-risk / breach** detection + tiered levels | ✅ | M10, `EscalationLevel` |
| DA pace / attempt-success / control-tower reads | 🟡 | real, but `on-time %` is phantom; UTC/IST date bug |
| **DA no-show** auto-detect + case + reassign | 🔴 | never-online DA invisible; `DA_ABSENT` unconsumed |
| **Cron-cutoff miss** detection + case | 🔴 | no emitter; dead case type |
| **Flight miss** exception case | 🔴 | re-bag exists, case unwired; M9 not live |
| **Severity bands / SLA timers / auto-escalation** on exceptions | 🔴 | **none** — M11 is a manual queue |
| **Notifications** (SMS/push/call to DA/supervisor/customer) | 🔴 | platform-wide gap |
| **DA accountability / penalty** on no-show / cash variance | 🔴 | `da_attributable` dead in practice |
| Loss / missing investigation & recovery | 🔴 | flag only, no workflow |

**Reading of the board:** the **failure-capture and SLA-detection halves are real**; the
**escalation half — timers, tiers, auto-routing, notifications — is not built**, and the two
one-day-critical triggers (**no-show**, **cron/flight miss**) are the least wired.

## 7. To make this matrix real — build order

1. **No-show detection that works** — reconcile the shift roster (who's expected) against who's
   actually online at shift start; emit a **shipment-scoped** no-show signal and open a case that
   auto-reassigns. _Closes A1/A2 — the CEO's own example._
2. **Cron & flight breach emitters** — have M5/M9 emit `CRON_MISSED` / `FLIGHT_MISSED` so the
   already-built M11 case types stop being dead. _Closes B1/B2._
3. **Escalation engine on M11** — add a severity field, an SLA timer (`@Scheduled` sweep of open
   cases), and auto-escalation SUPERVISOR → STATION_MANAGER → ADMIN when first-touch time lapses.
   _Turns the queue into a matrix._
4. **Notification channel** — SMS/push/call fan-out so escalations reach a human off-screen.
5. **Auto-RTO + DA accountability** — enforce max-attempt → RTO on a timer; make `da_attributable`
   drive a real penalty/coaching record.
6. **Fix the phantoms first** — populate `expected_eta` (so on-time % is real) and pin the control
   tower to IST — cheap, and they prevent a wrong number in front of the CEO.

_Items 1–3 are the difference between "we capture exceptions" and "we run an escalation matrix."_
