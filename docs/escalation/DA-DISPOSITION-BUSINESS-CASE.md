# DA Auxiliary Mode & Disposition States — Business Case / Go-No-Go

> **Status:** decision doc, no code. Companion to `DA-DISPOSITION-DESIGN.md`. This doc exists to answer
> one question honestly before any code is written: **should we let DAs raise arbitrary breaks (lunch,
> EV-charge, "other work") at all, and track disposition states — or not?** It is deliberately
> **skeptical**: it catalogues every real operational scenario where the naive version creates technical
> and debugging complexity, weighs the pros and cons, and lands on a recommendation. It is written to be
> usable *as the case for saying no* in the CEO conversation.
>
> **Bottom line up front:** do **not** ship the naive "DA raises an arbitrary break, system tracks it"
> version. Either ship the **reframed slice** in `DA-DISPOSITION-DESIGN.md` (churn-avoidance +
> coverage-safe reassignment + honest auxiliary accounting, with metrics as a GPS-reconciled byproduct),
> or defer the whole thing behind the higher-ROI Discussion-3 items (RTO-mid-transit, Global ETA, COD
> settlement). The naive version's headline benefit — utilisation metrics — is untrustworthy by
> construction, and its headline risk — a coverage cascade — is a new failure mode we don't have today.

## 1. The core concept (as originally discussed)

A DA raises a request — "lunch break," "EV charging," "pulled onto other company work" (auxiliary) — of
some duration. The station manager approves it (or the system auto-approves specific cases). The system
then either **holds** his territory (betting he returns soon) or **reassigns** it to neighbours (if he's
out too long). Throughout, the system records **disposition states** (available / on-task / on-break /
auxiliary) so the business can later report how each DA spent his shift — time collecting parcels, on
breaks, recharging the EV, etc.

## 2. Why this is a genuine question, not an obvious yes

Today the platform is **outcome-based and self-correcting.** A DA is handed a set of tasks; he must
complete them; **we do not care when or how he takes a break, only whether the tasks land.** If they
don't, the shortfall surfaces on its own — `ShiftEndJob` defers still-`QUEUED` tasks and M11 escalates.
The system needs no notion of *why* a DA was slow.

Adding disposition/auxiliary tracking is therefore a **philosophy change**: from measuring *outcomes* to
tracking *process*. That change has to earn itself, because:

- It does **not** change the outcome — a tracked break and an untracked break produce the same delivered
  parcel or the same exception.
- Its stated payoff (utilisation analytics from self-reported states) is **only as good as the
  self-reports**, and self-reports from a field workforce with an incentive to look busy are exactly the
  kind of data you cannot trust (§3.4).
- It introduces a **new decision surface** — hold vs vacate — that can be gamed or can fail
  catastrophically (§3.1), which the current model simply does not have.

## 3. Failure-scenario catalogue

Each scenario below is written as **business scenario → technical complexity it creates → why it's hard to
debug.** These are the concrete ways letting DAs take arbitrary breaks bites us.

### 3.1 The domino / cascade (the headline risk)
- **Business:** DA1 goes to lunch → his tasks land on DA2's queue → DA2 also goes to lunch → his (now
  larger) load lands on DA3 → … Eventually one DA inherits the territory of many, is physically unable to
  finish, and refuses / collapses — and *that* triggers a mass exception right at the end of the shift
  when there's no time to recover.
- **Technical complexity:** recursive reassignment; each hop **re-creates** the task on a new owner
  (append-only), so a single parcel accumulates a chain of `CANCELLED` copies plus one active row across
  several DAs; the physical parcel changes hands via repeated `CUSTODY_COLLECT` handoffs; the flood-fill's
  load-balancing assumptions break down when the pool of live neighbours keeps shrinking.
- **Debugging:** answering "why does DA5 hold this parcel and why is he overloaded" means replaying N
  reassignment events across N disposition rows and reconstructing the order in which breaks fired.
  Provenance is only recoverable via `order_ref` threaded through many `dispatch_queue` rows.

### 3.2 Parcel-at-lunch (in-custody + a deadline)
- **Business:** a DA carrying a flight-critical parcel goes on break with it in his bag. Hold → the parcel
  sits idle at a restaurant and risks its flight. Vacate → a neighbour is sent to `CUSTODY_COLLECT` at the
  DA's last GPS (the restaurant), where the DA may no longer be.
- **Technical complexity:** the SLA/cron clock keeps running on a parcel that is physically immobile; the
  collect location is a guess (last GPS vs task location).
- **Debugging:** the scan ledger says the parcel is with DA1 while it's physically at a lunch spot;
  reconciling "where is this parcel really" against a moving/stationary DA is genuinely hard.

### 3.3 Return-after-cutoff thrash
- **Business:** the DA returns minutes after we vacated; or takes repeated short on/off breaks.
- **Technical complexity:** restore → **double** custody churn (parcels move DA1→DA2, then DA2→DA1);
  repeated breaks → repeated flood-fills; a race between the auto-vacate job and the DA's "I'm back" tap.
- **Debugging:** territory ownership flip-flops within minutes; determining *which* `INTRADAY_OVERRIDE`
  proposal is authoritative at a given instant is non-trivial.

### 3.4 The trust gap / gaming (the headline reason the metrics are worthless)
- **Business:** a DA taps "I'm back" but isn't; declares 30 minutes and takes 60; or simply stops working
  without declaring any break at all.
- **Technical complexity:** the only defence is reconciling the claim against the `da_gps_ping` trail —
  but GPS gaps are ambiguous (dead battery vs no signal vs deliberately-off phone), so reconciliation is
  three-valued, not binary, and the robust version depends on the **unmerged** `od-da-hardening` risk
  layer.
- **Debugging:** you cannot distinguish "app crashed" from "gaming the system" without the plausibility
  layer; any metric built on the raw self-report is **confidently wrong**, which is worse than no metric.

### 3.5 Neighbour-unavailable orphans
- **Business:** enough adjacent DAs are out that a vacate finds **no** live neighbour to receive some
  hexes; those parcels get stuck (`DEFERRED`) and miss their promise.
- **Technical complexity:** coverage must gate the **approval**, not merely react at apply time — a
  capability the current absence flow lacks (it produces orphans reactively).
- **Debugging:** orphans surface late (at apply), and "why is this hex uncovered" requires knowing every
  neighbour's disposition **at that exact instant**.

### 3.6 Break during cron-lock / at-cron
- **Business:** a DA at the van-meeting vertex (VAN_MEETING cities) or mid hub-return vanishes on a break.
- **Technical complexity:** a van waits at a rendezvous for a DA who won't come; the cron is missed and,
  in VAN_MEETING mode, unrecoverable. Must be prevented by disallowing breaks in `CRON_LOCKED`/`AT_CRON`.
- **Debugging:** a missed cron cascades into missed flights downstream; tracing it back to "a DA took a
  break at the wrong moment" spans M5→M6→M9.

### 3.7 Auto-approve / manual / re-request races
- **Business:** the auto-approve timer, a manager's manual approval, and a DA's re-request all land close
  together.
- **Technical complexity:** multiplies the freshest-wins race the existing absence flow already handles;
  stale `PENDING` events can auto-apply against territory that has since changed.
- **Debugging:** determining which disposition event was "live" at a moment requires careful timestamp
  archaeology across auto and manual paths.

### 3.8 Extension creep
- **Business:** a DA extends a break in small increments, each re-approved, never quite crossing the
  vacate cutoff — holding his territory (and its coverage gap) open indefinitely.
- **Technical complexity:** needs hysteresis and a hard cumulative per-shift cap, or the hold state never
  resolves.
- **Debugging:** "why was this area uncovered for two hours with no reassignment" is buried in a chain of
  small extension events that each looked individually reasonable.

### 3.9 Shift-boundary interaction
- **Business:** a break spans shift-end; or a held territory / a spare DA exists as the shift closes.
- **Technical complexity:** `ShiftEndJob` defers `QUEUED` tasks and deliberately **preserves the other
  shift's roster** — a held/spare state must be cleaned in the right order relative to that.
- **Debugging:** disposition or territory state leaking across the shift boundary produces "ghost" holds
  the next shift can't explain.

### 3.10 Contention with the existing absence flow
- **Business:** a DA is heartbeat-flagged `ABSENT` while he also has a pending break; or absence
  reassignment and a disposition hold act on the same DA at once.
- **Technical complexity:** **two subsystems writing the same tables** (`da_hex_assignment`,
  `dispatch_queue`). Folding both into one `da_disposition_event` + one service is the mitigation, but the
  interaction surface is real.
- **Debugging:** reconstructing state means untangling append-only rows written by two different code
  paths.

### 3.11 Spare-pool starvation / fairness
- **Business:** the relief pool is empty exactly when needed (everyone territoried or on break), or one
  unlucky spare gets dumped with all the overflow.
- **Technical complexity:** overflow routing needs a fairness/load rule; an empty pool must fail loud, not
  silent.
- **Debugging:** "why did this deferred task go to that spare" is opaque without an explicit routing log.

### 3.12 EV-charging is not a discretionary break
- **Business:** for an EV fleet, charging is **mandatory, location-bound, and duration-known** — not a
  choose-when-you-like lunch. Conflating the two mis-models it.
- **Technical complexity:** charging should be *scheduled* (into the morning windows, at known stations),
  not raised ad-hoc — a different mechanism.
- **Debugging:** (also a pro-structure argument — see §4) treating charging as a random break makes
  charge-related coverage gaps look like undisciplined breaks.

### 3.13 Appointment / B2B time-windowed deliveries in a held territory
- **Business:** a time-windowed (appointment / B2B) delivery sits in a territory we're holding; the hold
  makes it miss its window.
- **Technical complexity:** the hold decision must be aware of per-task time windows, not just cron/flight
  deadlines.
- **Debugging:** a missed appointment traced back to "we held the territory for a break" is a customer-
  facing failure with a non-obvious internal cause.

### 3.14 Notification reliability
- **Business:** we vacate a DA who never received warn-1/warn-2 (push failed, phone off) → he returns to
  find his territory gone and disputes it.
- **Technical complexity:** the grace ladder is only fair if delivery is confirmed; needs delivery
  receipts before a vacate counts as "warned."
- **Debugging:** "did he actually get the warning" is unanswerable without receipt tracking.

### 3.15 Labor / compliance surface
- **Business:** the moment we systematically record break time, we've created a **labor record** — which
  can pull in mandated-break, overtime, and working-hours obligations we may not want to take on.
- **Technical complexity:** none, initially — but the *data existing* changes our legal posture.
- **Debugging:** not a debugging issue; a governance one, and a real con.

## 4. Pros of adding

- **DA autonomy / morale / retention** — drivers can plan their day, feel trusted; matters for a field
  workforce that's expensive to churn.
- **Churn reduction for short absences** — *if* the hold is done right, we stop the wasteful
  reassign-then-manually-unwind cycle for a 20-minute step-away (this is the one operational win that
  holds up).
- **Honest auxiliary accounting** — company-pulled work shouldn't count against a DA's delivery numbers;
  this is legitimate and not self-serving (the *company* initiates it).
- **Manager visibility** — a live picture of who's out and why.
- **EV-charging foundation** — a fleet on EVs *needs* charging modelled somewhere; this is a natural home
  (as a scheduled, not discretionary, activity — §3.12).
- **Utilisation analytics** — genuinely useful **only if trustworthy**, i.e. only in the GPS-reconciled
  form, never from raw self-reports.

## 5. Cons of adding

- **Trust / gaming** — the flagship metric is untrustworthy by construction; the trustworthy version has a
  **hard dependency on the unmerged `od-da-hardening`** risk layer.
- **New cascade failure mode** — §3.1 is a class of outage we do not have today.
- **Large cross-cutting build** — backend (`dispatch`, `grid`) + web station console + driver app — with
  **high opportunity cost** against RTO-mid-transit [L], Global ETA [M–L], and COD settlement [L], all of
  which have clearer ROI.
- **Multiplied debugging / provenance complexity** — §§3.1, 3.3, 3.7, 3.10 all make "what happened to this
  parcel/territory" materially harder to answer.
- **Philosophy shift** — away from the robust, self-correcting outcome model toward process-tracking that
  doesn't change outcomes.
- **Labor / compliance exposure** — §3.15.
- **Precise-but-wrong metrics** — the worst outcome: numbers that look authoritative and drive decisions
  while being fiction.

## 6. Recommendation

**Do not build the naive version** (DA raises an arbitrary break; system tracks self-reported disposition
time as truth). It fails cost/benefit: its main benefit is untrustworthy and its main risk is a new
outage class.

**Build the reframed slice instead** (`DA-DISPOSITION-DESIGN.md`), *or defer entirely* behind the
higher-ROI Discussion-3 items. The reframed slice keeps only what earns its place:

- **Graceful hold** — avoid churn on short absences (the real operational win).
- **Coverage-gated vacate + per-cluster concurrency budget** — makes reassignment *safe*, killing the
  cascade (§3.1) and the orphan problem (§3.5) by construction.
- **Two-notification grace ladder** — fairness + audit (§3.14, with receipts).
- **Honest auxiliary accounting** — the one legitimate, non-gameable-in-a-harmful-direction signal.
- **Minimal relief-pool** — gives spares a real job *and* fixes today's orphan-escalation dead-end.
- **Disposition metrics as a GPS-reconciled byproduct** — never a trusted self-report; gated on the
  hardening layer.

The framing for the CEO: this is not "break tracking." It is *"stop wastefully reassigning territory for
short absences, make every reassignment coverage-safe, and account for company-pulled DA time honestly."*
The disposition states are a consequence of that machine, reconciled against GPS — not the point.

## 7. Decision matrix

| Dimension | Naive full build | Reframed slice (recommended) | Do nothing (defer) |
|---|---|---|---|
| Build cost | High (backend + web + app) | Medium (clones existing absence patterns) | Zero |
| Opportunity cost vs RTO/ETA/COD | High | Medium | None |
| Trust of resulting metrics | **Low** (self-report) | Medium (GPS-reconciled; gated on hardening) | N/A |
| Cascade / coverage safety | **Poor** (new outage class) | **Strong** (coverage-gated + concurrency budget) | Unchanged (today's model) |
| Churn on short absences | Improved but risky | **Improved & safe** | Unimproved (heartbeat→full reassign) |
| Auxiliary accounting | Yes (untrusted) | **Yes (honest)** | No |
| Labor/compliance exposure | Yes | Yes (contained; explicit non-goal) | No |
| Dependency on `od-da-hardening` | For any trusted metric | For full metrics (v1 works coarse) | None |
| Reversibility | Hard (embedded philosophy shift) | Medium | Trivial |

**Verdict:** reframed slice if we build; otherwise defer. Not the naive version.
