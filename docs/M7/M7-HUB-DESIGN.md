# M7 — Hub Operations & Sortation: Design Doc

| Field | Value |
|-------|-------|
| **Module** | M7 — Hub Operations & Sortation (`hub`, `com.oneday.hub`) |
| **Status** | Draft v0.1 — full hub lifecycle (inbound receive → sort → stand → bag → manifest → dispatch), both directions |
| **Depends on** | `common` (event contracts, `BaseEntity`, `ShipmentState`), `barcode` / M8 (scan ledger + bag QR), `orders` / M4 (shipment record, confirmed weight, SLA) |
| **Consumed by** | M6 (sorted-for-delivery → loop binding; receives van unload), M8 (bag labels), M9 (flight bag + manifest handover), M4 (hub state transitions), M10 (per-leg SLA timestamps), M11 (mis-sort / overload exceptions) |
| **Source** | `docs/PRD-ONE-DAY-DELIVERY.md` §9, §10, §20 (H1–H3, A4) · `docs/MODULES.md` M7 · `docs/M6/M6-ROUTING-DESIGN.md` §16 (custody boundary) · `docs/M8-BARCODE-DESIGN.md` |

> **What M7 is, in one line.** The hub is the **fixed cross-dock** — the counterpart to M6's mobile cross-dock van. Everything that physically rests inside a city's hub between the first-mile and the last-mile passes through M7: it receives parcels at the dock, reads their barcode, decides the **stand** they go to, builds the **flight bag** (origin) or the **delivery stage** (destination), generates the **manifest**, and releases the bag to the airport shuttle (M9) or the parcels to the drop van (M6). M7 owns *everything inside the hub walls*.

> **How to read this doc.** Three parts.
> **Part I — What & boundaries:** §1–§4. What M7 does, what it owns, and the design decisions.
> **Part II — The sort engine:** §5–§12. The two directions, receiving, stand/bag/manifest, reschedule, flight reactions, overload, same-city shortcut.
> **Part III — Integration & governance:** §13–§18. Cross-module RACI (where this doc resolves two live M5↔M6↔M7 contract conflicts), contracts, constraints, open questions, phase plan, testing.
> Design decisions are tagged `M7-D-xxx`; constraints `Cn`; open questions `Qn`.

---

## Table of Contents

**Part I — What & boundaries**
1. [What M7 Does](#1-what-m7-does)
2. [Scope & Ownership](#2-scope--ownership)
3. [Industry Context — How Sortation Is Actually Solved](#3-industry-context--how-sortation-is-actually-solved)
4. [Key Design Decisions](#4-key-design-decisions)

**Part II — The sort engine**
5. [The Hub as a Fixed Cross-Dock](#5-the-hub-as-a-fixed-cross-dock)
6. [Inbound Receiving — Three Arrival Modes](#6-inbound-receiving--three-arrival-modes)
7. [Outbound Sort — Stand, Flight Bag, Manifest](#7-outbound-sort--stand-flight-bag-manifest)
8. [Inbound Sort — Delivery Staging & Hub-Collect](#8-inbound-sort--delivery-staging--hub-collect)
9. [Low-Weight Flight-Bag Reschedule](#9-low-weight-flight-bag-reschedule)
10. [Reacting to Flight Status](#10-reacting-to-flight-status)
11. [Hub Overload Back-Pressure](#11-hub-overload-back-pressure)
12. [The Same-City Shortcut](#12-the-same-city-shortcut)

**Part III — Integration & governance**
13. [Cross-Module RACI & Contract Reconciliation](#13-cross-module-raci--contract-reconciliation)
14. [Contracts — Events, APIs, Data Model, Ports](#14-contracts--events-apis-data-model-ports)
15. [Constraint Catalogue](#15-constraint-catalogue)
16. [Open Questions](#16-open-questions)
17. [Phase Plan](#17-phase-plan)
18. [Testing & Simulation](#18-testing--simulation)

---

# Part I — What & boundaries

## 1. What M7 Does

M7 governs all physical-and-digital operations **inside a city hub**. A parcel that touches a hub touches M7 twice in its life:

- At the **origin hub** (outbound), where a first-mile parcel is consolidated for its flight: receive → sort by destination/flight → flight bag → stand → manifest → dispatch to airport.
- At the **destination hub** (inbound), where a landed parcel is broken down for last-mile: receive → sort by delivery territory → delivery stage → hand to drop van (M6) or hold for hub-collect.

These are the **same engine** run with two different sort keys (`M7-D-001`). The hub is symmetric (PRD §4.4, §9.4): origin consolidates *toward a flight*, destination de-consolidates *toward a DA*. One sort plan abstraction, one stand model, one manifest model — parametrised by direction.

M7 is **workflow-heavy for human operators** (the hub operator scans, places, seals) and an **event consumer + producer**: it reacts to M8 scan events (parcel arrived) and M9 flight events (cutoff shifted / flight cancelled), and it emits the hub events M6/M9/M4/M10 react to.

M7 produces, per city, per hub, per day: a stand assignment for every received parcel; flight bags grouped by flight with system-generated manifests; a stream of "sorted for delivery" parcels that M6 binds to drop-van loops; bag reschedule decisions; and hub-overload back-pressure signals.

---

## 2. Scope & Ownership

### 2.1 In scope — M7 owns all of this

| Area | What M7 owns |
|------|--------------|
| **Inbound receiving** | The dock: accepting parcels from three arrival modes (van unload, self-drop, airport shuttle), reconciling against expectation, taking hub custody. |
| **Sortation** | The sort plan (`destination key → stand`), versioned per city/direction; resolving each parcel's stand from its barcode sort key + the live sort plan. |
| **Stand management** | Physical stand config, occupancy, capacity, overflow → reassignment + relabel workflow. |
| **Flight bags** | Bag creation (group by flight), bag lifecycle (OPEN→SEALED→DISPATCHED→HANDED_OVER), weight accumulation, the dual-number (flight + stand) bag QR via M8. |
| **Manifests** | System-generated per-bag, per-flight manifest at seal time; the append-only manifest record handed to M9/airline. |
| **Reschedule** | The low-weight bag → next-flight decision, gated on per-parcel SLA (`M7-D-006`, A4). |
| **Flight reactions** | Reacting to M9 delay/cancellation: reopening windows, forced re-bag onto next flight. |
| **Overload** | Hub-overload detection and back-pressure signalling — never silently drop SLA (`M7-D-007`, H3). |
| **Delivery hand-off prep** | Producing the "sorted for delivery" feed M6 consumes; the hub-collect shelf for HUB_COLLECT shipments. |
| **Operator UI contract** | The hub-operator scan-gun/console workflow (parallel to M5 owning the DA app, M6 the driver app). |

### 2.2 Out of scope

- **Barcode/label generation & the scan ledger** — M8. M7 *calls* M8 to build bag QR labels and *consumes* M8 scan events; it never stores scans itself.
- **Flight schedule, cutoff computation, flight assignment** — M9. M7 consumes "which flight + what cutoff" via a port; it does not pick flights.
- **The van and its manifest** — M6. M7's responsibility ends at "parcel staged on the delivery stand / loaded count handed to the dock." M6 owns which parcel rides which loop and the van-side load scan (`M7-D-004`, see §13).
- **The shipment state machine** — M4 owns it; M7's hub events *drive* transitions, M4 owns the transition.
- **SLA judgement** — M10. M7 *feeds* leg timestamps; M10 classifies GREEN/AMBER/RED.
- **The hub↔airport shuttle timetable** — M6 owns the shuttle cadence; M7 hands sealed bags to it.
- **Building the operator mobile/console app** — a separate client; M7 defines its contract and serves its API.

### 2.3 Gaps this module closes in the codebase

- **`HubSortPort` is currently buffer-stubbed in M6.** `routing.service.port.BufferedHubSortPort` reads an `inbound_parcel` buffer populated by a *provisional* `ParcelSortedForDeliveryEvent` (`M6-D-015`). **M7 is the real producer of that feed** — building M7 means the deliver side stops being a stub (`M7-D-002`).
- **No hub physical model exists today.** No stand, no flight bag, no manifest, no sort plan entity exists anywhere in the repo. M7 introduces all of them.

---

## 3. Industry Context — How Sortation Is Actually Solved

A hub is a **cross-dock**: goods arrive, are re-sorted by outbound destination, and leave — ideally with minimal dwell and no long-term storage. The sortation core is not an optimisation problem like M6's VRP; it is a **deterministic routing-table lookup** (`sort_key → outbound lane`) wrapped in **physical capacity management** and **wave/batch timing**.

| Our concept | Industry term | What it is |
|-------------|---------------|-----------|
| Stand | **Sort destination / chute / pigeonhole** | The physical lane a parcel is directed to. In automated facilities this is a chute fed by a sorter belt; for us it is a numbered floor stand a human places on. |
| Sort plan | **Sort directory / nixie chart** | The lookup `destination → lane`. Updated when flight assignment or the bag map changes. |
| Flight bag | **Container / ULD-equivalent / gaylord** | The consolidation unit grouped by next leg (flight). |
| Manifest | **Container manifest / BOL** | The system record of contents handed to the carrier. |
| Wave | **Sort wave / cut** | A timed batch of sorting aligned to outbound departures (flight cutoffs). |

**How the industry does it:** FedEx/UPS hubs run a fixed **directory sort** (barcode → chute) on high-speed sorters; the intelligence is in the *directory* (kept current with the flight/linehaul plan) and in **wave planning** (which parcels must clear the sort by which cutoff). Amazon sort centers run the same directory pattern with `sort_key → stack` and **rate/health monitoring** for overload. Our hub is the **manual, low-volume version**: a human scan gun resolves the stand from the barcode, and the "sorter" is the operator. That makes M7 a **CRUD-plus-workflow** service, not a solver — its hard parts are (a) keeping the sort directory correct as flights change, (b) the bag-seal/manifest discipline, (c) the SLA-gated reschedule, and (d) graceful overload back-pressure.

**Our choice (`M7-D-001`):** a single direction-parametrised **directory sort** — `(direction, sort_key) → stand`, backed by a versioned `sort_plan`. No solver. The reschedule check (§9) and overload back-pressure (§11) are the only non-trivial logic.

---

## 4. Key Design Decisions

### Engine & model

**M7-D-001 — One symmetric, direction-parametrised sort engine.** Origin (outbound, sort to flight bag) and destination (inbound, sort to delivery stand) are the same lookup `(direction, sort_key) → stand`, differing only in the sort key (`dest_hub + flight` vs `dest_hex / DA territory`) and the consolidation unit (flight bag vs delivery stage). One `sort_plan`, one `stand` model, one manifest model. Resolves PRD §4.4 symmetry.

**M7-D-002 — M7 is the real backing for M6's `HubSortPort`.** The deliver-side feed M6 currently reads from its buffer stub is produced by M7: on destination-sort completion per parcel, M7 emits a per-parcel `PARCEL_SORTED_FOR_DELIVERY` hub event carrying `(parcelId, cityId, destinationHexId, validDate, sortedAt, slaDeadline)` — the exact shape of the provisional `ParcelSortedForDeliveryEvent` M6 already consumes. Building M7 swaps the stub for the real feed with **no change to M6's binder**.

**M7-D-003 — The stand resolves from the barcode, not from a parcel lookup.** Per PRD §10, the parcel's barcode payload carries `dest_hub` and `sort_key`; the hub scan resolves the stand from `sort_key + live sort plan` (FR-7). M7 does not need to round-trip to M4 for routing data on the hot scan path — the label is self-describing (the M8 contract guarantees it). M7 *does* read M4 for confirmed weight (bag weight, §9) and SLA (reschedule gate), off the hot path.

### Custody boundary (the conflict resolutions — see §13)

**M7-D-004 — In the M6 model, M7 does NOT hand parcels to the drop van; it stages them. M6 loads.** The legacy M5 design (v1.1) has M7 emit `DROP_VAN_HANDOFF` to drive `HANDED_TO_DROP_VAN`. The newer M6 design makes the van a mini-hub that owns its own load: M6 binds the parcel to a loop and the hub operator records the `VAN_LOAD` scan (`M6-D-014`). **This doc adopts the M6 model.** M7's job ends at "sorted for delivery, staged on the delivery stand"; `HANDED_TO_DROP_VAN` is driven by M6's `VAN_LOAD` scan, not an M7 event. `HubEventType.DROP_VAN_HANDOFF` is therefore **deprecated** (kept in the enum for back-compat; not emitted by M7 v1). *To confirm with M5 + M6 owners (Q5).*

**M7-D-005 — The van-unload scan IS the hub in-scan; M7 does not double-scan.** M6 originates `VAN_UNLOAD` for first-mile collections arriving at the dock (`M6-D-014`), which already drives `HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB`. M8's `HUB_ORIGIN_IN` describes the *same* physical transition. **One scan, not two:** for van arrivals M7 begins sortation off the `AT_ORIGIN_HUB` state (consumed from M4 / the scan ledger), and reserves `HUB_ORIGIN_IN`/`SELF_DROP_ACCEPTED` for **non-van** arrivals (self-drop). This avoids a duplicate ledger entry for the most common path. *Confirm the single-scan rule with M8 (Q6).*

### Bags, manifests, reschedule

**M7-D-006 — Reschedule is SLA-gated on the *binding* parcel, computed at decision time.** A low-weight bag may move to a later flight only if **every parcel currently in the bag** still meets its SLA under the later departure (PRD §9.3, A4). M7 evaluates `max over parcels(needed_arrival) vs later_flight.arrival + dest_tail` at the moment of decision against the live bag contents — not a frozen snapshot — because bag contents change until seal. If even one parcel breaches → keep on original flight. The underweight *threshold* is config per (origin, dest, airline) — `Q1`.

**M7-D-007 — Overload never drops SLA; it back-pressures upstream and escalates.** When inbound rate or stand occupancy exceeds capacity, M7 (a) emits a `HUB_OVERLOAD_ALERT` to M10 + station manager, and (b) raises a **booking-throttle signal** consumable by M4 (advisory in v1). It never silently fails to sort a parcel (PRD §9.5, H3). The throttle *policy* (how hard M4 throttles, add-a-wave vs add-a-shift) is ops-owned and deferred — M7 provides the signal and the flag.

**M7-D-008 — Bags and manifests are append-only; stand reassignment is a new revision, never a mutation.** Per NFR-1: a sealed bag's manifest is immutable; a stand-full move writes a `stand_reassignment_audit` row recording old + new stand and a *new* bag label (via M8), it does not edit the bag's stand in place beyond a current-pointer. Manifest regeneration after reschedule supersedes (new manifest row), never mutates.

### Unbuilt-dependency seams

**M7-D-009 — M9 stubbed behind `FlightAssignmentPort`.** M9 is unbuilt; until it lands, a stub returns a deterministic flight + cutoff per (dest city, ready time) so M7 builds and tests bagging end-to-end now (mirrors M6's `FlightCutoffPort`). M7 also *consumes* `oneday.flight.events` (delay/cancel) — dormant until M9 produces.

**M7-D-010 — M8 stubbed behind a barcode port.** M8's label/QR build and `getLatestScanAtPoint` are called through a port; a No-op/local impl until M8 ships. M7 consumes `oneday.scan.events` (arrival) — dormant until M8 produces; for now the M6 custody scans + M4 states drive M7 in the integrated demo.

**M7-D-011 — Injectable clock.** All time via an injected `java.time.Clock` (IST) so a full hub day simulates in seconds (mirrors `M6-D-013`). Lands in P1.

---

# Part II — The sort engine

## 5. The Hub as a Fixed Cross-Dock

```
                         ORIGIN HUB (outbound consolidation)
   first-mile in ──►  DOCK ──► SORT(by flight) ──► FLIGHT BAG ──► STAND ──► MANIFEST ──► airport shuttle (M6) ──► M9
   (van unload / self-drop)                        group by flight     seal + label        hand to GHA

                         DESTINATION HUB (inbound de-consolidation)   [same engine, mirror]
   landed in   ──►  DOCK ──► SORT(by DA territory) ──► DELIVERY STAGE ──► drop van (M6 binds & loads)
   (airport shuttle)                                   stage by loop/zone   OR ──► HUB-COLLECT shelf (customer pickup)
```

**The two sort keys (`M7-D-001`):**

| Direction | Trigger state (M4) | Sort key | Consolidation unit | Exit |
|-----------|--------------------|----------|--------------------|------|
| **OUTBOUND** (origin) | `AT_ORIGIN_HUB` | `dest_hub + assigned_flight` | flight bag → stand | `IN_TAKEOFF_BAG` → `DISPATCHED_TO_AIRPORT` (shuttle) |
| **INBOUND** (dest) | `AT_DEST_HUB` | `dest_hex` (→ DA territory) | delivery stage (by drop-van loop) | `HANDED_TO_DROP_VAN` (M6) or `AWAITING_HUB_COLLECT` |

The state names already exist in `common.domain.enums.ShipmentState` — M7 aligns to them. The hub spans these M4 states:

```
 AT_ORIGIN_HUB → ORIGIN_HUB_PROCESSING → IN_TAKEOFF_BAG → DISPATCHED_TO_AIRPORT   (outbound)
 AT_DEST_HUB   → DEST_HUB_PROCESSING   → HANDED_TO_DROP_VAN | AWAITING_HUB_COLLECT  (inbound)
```

M7 drives the **processing** transitions (`*_HUB_PROCESSING`, `IN_TAKEOFF_BAG`, `DEST_SORT_COMPLETE`); the *exit* transitions are driven by the next owner's scan (shuttle dispatch → M6/M9; `VAN_LOAD` → M6; hub-collect → M8 `HUB_COLLECT_COMPLETED`).

---

## 6. Inbound Receiving — Three Arrival Modes

A parcel enters the hub by one of three routes. M7's dock reconciles each against an expectation and takes custody.

| Mode | Source | Scan / signal | M4 result | M7 action |
|------|--------|---------------|-----------|-----------|
| **Van unload** (first-mile) | M6 drop at dock | `VAN_UNLOAD` (M6-originated, `M7-D-005`) | `→ AT_ORIGIN_HUB` | reconcile vs van manifest; begin **outbound** sort |
| **Self-drop** (first-mile) | customer brings to hub | `SELF_DROP_ACCEPTED` (M8) | `AWAITING_SELF_DROP → AT_ORIGIN_HUB` | begin **outbound** sort |
| **Airport shuttle** (last-mile) | landed bag, shuttle back | `HUB_DEST_IN` (M8) | `LANDED/DISPATCHED_TO_HUB → AT_DEST_HUB` | break bag; begin **inbound** sort |

**Reconciliation at the dock (`C-recon`).** For van unloads, M7 receives M6's manifest of what *should* arrive and reconciles against what *did*. A shortfall (expected, not scanned) or surplus (scanned, not expected — a mis-sort) raises a discrepancy → M11 + M10 (mirrors M6's handoff reconciliation, `M6-D-018`). For airport arrivals, M7 reconciles the broken bag against its M9 manifest.

**Custody continuity (`C12`-equivalent).** Between van unload and the next outbound scan, the parcel is "at the hub, on stand X" — never nowhere. The hub is a custody node exactly as the van is.

---

## 7. Outbound Sort — Stand, Flight Bag, Manifest

The origin-hub path: consolidate a first-mile parcel toward its flight.

**7.1 Resolve flight + stand.** On `AT_ORIGIN_HUB`:
1. Read the barcode sort key (`dest_hub`, `sort_key`) — self-describing (`M7-D-003`).
2. Resolve the **assigned flight** via `FlightAssignmentPort` (M9; stub `M7-D-009`) — the earliest flight to `dest_hub` satisfying the parcel's SLA and the bag cutoff.
3. Resolve the **stand** from the live `sort_plan` for `(OUTBOUND, dest_hub+flight)`. Emit `STAND_ASSIGNED` (→ operator console, M8 if relabel needed). Transition `→ ORIGIN_HUB_PROCESSING`.

**7.2 Build the flight bag.** Parcels for the same `(flight_no, flight_date, dest_hub)` accumulate into one **flight bag** sited on the resolved stand:
- Bag created lazily on first parcel for that flight → `BAG_CREATED` (carries `bag_id`, flight, stand). Lifecycle `OPEN → SEALED → DISPATCHED → HANDED_OVER`.
- Each added parcel: `bag_item` row, bag `weight_paise`/`weight_grams` and `parcel_count` incremented (weight from M4 confirmed weight), parcel `→ IN_TAKEOFF_BAG`.
- **Stand overflow (`M7-D-008`, H2):** if the stand is full, operator reassigns the bag to an alternate stand → `stand_reassignment_audit` (old + new) + **new bag label** via M8 (the dual-number QR: flight + new stand). The bag's `current_stand` pointer moves; history is append-only.

**7.3 Seal + manifest.** At the bag cutoff (or operator seal):
- Bag `→ SEALED`; a **system-generated manifest** (`bag_manifest`, append-only) snapshots every `parcel_id` + dest data → `M8.buildBagLabel` data + `MANIFEST_GENERATED`.
- Bag handed to the hub↔airport shuttle (M6) → `DISPATCHED_TO_AIRPORT`; later `AIRPORT_HANDOVER` / `GHA_ACCEPTANCE` (M8) → `HANDED_OVER`.
- The sealed bag + manifest is the M9 handover artefact.

**7.4 The wave.** Bag cutoffs (from M9) define the **sort waves**: every parcel for flight F must be sorted-and-bagged before F's bag cutoff. M7 tracks each open bag's countdown; a parcel arriving after its only feasible flight's cutoff is an **overflow/reschedule** case (§9 / §11), never a silent miss.

---

## 8. Inbound Sort — Delivery Staging & Hub-Collect

The destination-hub path: break a landed parcel down toward last-mile (mirror of §7).

**8.1 Break the bag, resolve the delivery target.** On `AT_DEST_HUB` (`HUB_DEST_IN`):
1. Reconcile the landed bag against its M9 manifest (§6).
2. Per parcel: resolve `dest_hex` from the barcode `sort_key` (city + pincode → hex via M3's serviceability, off hot path or cached). Resolve the **delivery stand** from `sort_plan` for `(INBOUND, dest_hex_zone)` — parcels stage by drop-van loop/zone. Transition `→ DEST_HUB_PROCESSING`.

**8.2 Branch on drop type.** From the shipment record (M4):
- **`DA_DELIVERY`** → stage on the delivery stand and emit **`PARCEL_SORTED_FOR_DELIVERY`** (`M7-D-002`) → M6 binds it to a drop-van loop and later records `VAN_LOAD` (which drives `HANDED_TO_DROP_VAN`, `M7-D-004`). M7 also emits `DEST_SORT_COMPLETE` per city/wave for M10 visibility.
- **`HUB_COLLECT`** → place on the **hub-collect shelf**, transition `→ AWAITING_HUB_COLLECT`, notify customer (via notification command). Customer arrival → M8 `HUB_COLLECT_COMPLETED` → `HUB_COLLECTED` (terminal). M7 owns the shelf location + shelf-occupancy, not the customer interaction.

**8.3 Why M7 stages but M6 loads.** The destination stand is the *handoff buffer* between the fixed hub and the mobile van. M7 guarantees "the right parcels are staged, by loop"; M6 owns "which loop, loaded when, in reverse stop order." This is the clean seam that `M7-D-004` formalises and that M6's `HubSortPort` already expects.

---

## 9. Low-Weight Flight-Bag Reschedule

PRD §9.3 / A4. A flight bag that is **underweight** near cutoff is a cost inefficiency (a half-empty bag flies). M7 *may* defer it to a later flight — but only when SLA-safe.

```
At bag cutoff approach, if bag.weight < underweight_threshold(origin, dest, airline):    // Q1
    later = FlightAssignmentPort.nextFlightAfter(this_flight, dest_hub)
    safe = ∀ parcel ∈ bag.items:
              needed_arrival(parcel)  ≥  later.arrival + dest_hub_tail            // M7-D-006
              // needed_arrival from M4 SLA / M10 commitment; dest_hub_tail = sort+stage+drop budget (Q2)
    if safe:  move bag → later flight  → re-resolve stand → regenerate manifest → BAG_RESCHEDULED
    else:     keep on original flight (one tight parcel pins the whole bag)
```

- **Evaluated against live contents at decision time** (`M7-D-006`) — bag contents change until seal.
- The **earliest-deadline parcel pins the bag** (PRD: "earliest-received parcel must still be on time"; we generalise to the *tightest-SLA* parcel, which is the correct constraint).
- `BAG_RESCHEDULED` → M9 (re-handover plan), M10 (leg re-baseline). Append-only: new manifest supersedes.
- The `dest_hub_tail` budget is the same end-to-end timing budget M6 flagged as Q2 — **M7, M9, M10 must agree the split** (§16 Q2).

---

## 10. Reacting to Flight Status

M7 consumes `oneday.flight.events` (M9). A flight is not static; its status reshapes M7's windows:

| M9 event | Effect on M7 |
|----------|--------------|
| **`DELAYED`** | Bag cutoff shifts later → the window for adding parcels / rescheduling *reopens*. Re-evaluate open bags for this flight; pull in parcels that now fit; re-baseline manifest if not yet sealed. |
| **`DEPARTED`** | Bag for this flight is gone; any not-yet-bagged parcel for it must re-bag onto the next flight (forced reschedule, SLA re-checked, §9). |
| **`LANDED`** | Destination side: shuttle pulls the bag back; inbound sort (§8) begins on `HUB_DEST_IN`. |
| **`CANCELLED`** (via `RTO_IN_TRANSIT`/status) | **Forced re-bag** of every parcel onto the next available flight; each re-checked for SLA; breaches → M10 + M11 (rebooking flow). Never auto-drop. |

A delay that moves the cutoff is the *good* case (more slack); a cancellation is the back-pressure case. Both flow through the same re-bag + SLA-recheck path as §9, just triggered involuntarily.

---

## 11. Hub Overload Back-Pressure

PRD §9.5 / H3 — the principle is **never silently drop an SLA**; surface the condition for action.

**11.1 Detection.** M7 maintains a rolling `hub_load_snapshot` per (hub, wave): inbound arrival rate, parcels awaiting sort, stand occupancy %, and projected clear-by-cutoff. Overload = projected sort completion slips past a wave's cutoff, or stand occupancy crosses a high-water mark.

**11.2 Response ladder (`M7-D-007`):**
1. **Flag + alert** — `HUB_OVERLOAD_ALERT` → M10 (dashboard amber/red) + station-manager notification. Always.
2. **Back-pressure upstream** — emit a **booking-throttle signal** consumable by M4 (advisory v1): slow/pause new bookings for affected city-pairs until the wave clears.
3. **Ops levers** (out of system in v1, surfaced as recommendations): add a sort wave, call in an extra shift, add a shuttle run (→ M6). The *policy* of which lever, when, is an ops playbook (H3) — M7 provides the signal, not the decision.
4. **Escalate, never discard** — a parcel that cannot clear its wave is escalated (M10 + M11), exactly like M6's `LOOP_OVERFLOW`. It still sorts; its SLA risk is owned by M10.

This mirrors M6's overflow philosophy (`M6-D-017`) — symmetric back-pressure at the fixed cross-dock.

---

## 12. The Same-City Shortcut

`HubEventType.SAMECITY_OUTBOUND` already exists. An intra-city shipment (origin city == dest city) **does not fly**: origin hub *is* the dest hub. M7 detects this at outbound sort (§7.1) and **short-circuits the flight path**:

```
AT_ORIGIN_HUB → ORIGIN_HUB_PROCESSING → [same-city detected] → SAMECITY_OUTBOUND
                                                              → (skip flight bag / airport)
                                                              → DEST_HUB_PROCESSING (inbound sort, §8)
                                                              → HANDED_TO_DROP_VAN | AWAITING_HUB_COLLECT
```

No flight assignment, no bag, no shuttle, no manifest — the parcel is sorted straight to the delivery stand and re-enters §8. `SAMECITY_OUTBOUND` signals M4/M10 to collapse the air legs of the SLA. This is the simplest happy path through the hub and worth getting right early (it needs no M9).

---

# Part III — Integration & governance

## 13. Cross-Module RACI & Contract Reconciliation

The hub sits at the centre of the custody chain; getting its boundaries right is what unblocks M9/M10/M11. **Two live contract conflicts** between the existing M5 (v1.1) and M6 (v0.3) designs land on M7's desk — this doc resolves both.

| Concern | M7 (hub) | M6 (van) | M8 (scan) | M9 (air) | M4 (state) | M5 (DA) |
|---------|----------|----------|-----------|----------|-----------|---------|
| Inbound receive (dock) | **Own** | unload side | record scan | — | transition | — |
| Sort directory (`sort_key→stand`) | **Own** | — | — | feeds flight | — | — |
| Stand / bag / manifest | **Own** | — | bag QR util | consume manifest | — | — |
| Flight assignment / cutoff | consume | consume | — | **Own** | — | — |
| "Sorted for delivery" feed | **Own** (produce) | consume (bind) | — | — | — | — |
| Van load (stand→van) | stage only | **Own** (`VAN_LOAD`) | record scan | — | transition | — |
| `HANDED_TO_DROP_VAN` trigger | ~~emit~~ → **M6's scan** | **Own** | record | — | transition | consume |
| Hub-collect shelf | **Own** | — | `HUB_COLLECT_COMPLETED` | — | transition | — |
| Reschedule decision | **Own** | — | — | re-handover | — | — |
| Overload back-pressure | **Own** | — | — | — | throttle target | — |
| SLA judgement | feed | feed | feed | feed | feed → **M10** | feed |

**Conflict 1 — Who drives `HANDED_TO_DROP_VAN`? (Q5)**
M5 v1.1 §11.1 says *M7* emits `DROP_VAN_HANDOFF`. M6 v0.3 says *M6* owns the van load (`VAN_LOAD` scan). **Resolution (`M7-D-004`):** adopt the M6 model. M7 stages + emits `PARCEL_SORTED_FOR_DELIVERY`; M6 binds and its `VAN_LOAD` scan drives `HANDED_TO_DROP_VAN`. `DROP_VAN_HANDOFF` is deprecated (not emitted v1). M5 then consumes the resulting `HANDED_TO_DROP_VAN` state-change (M4 re-emit) exactly as its §11.1 already expects — only the *producer* changes, not M5's consumer.

**Conflict 2 — `VAN_UNLOAD` vs `HUB_ORIGIN_IN` double-scan? (Q6)**
Both claim to drive `HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB`. **Resolution (`M7-D-005`):** one physical scan. Van arrivals → M6's `VAN_UNLOAD`; M7 sorts off the `AT_ORIGIN_HUB` state. `HUB_ORIGIN_IN` reserved for non-van (self-drop is its own `SELF_DROP_ACCEPTED`). No duplicate ledger row for the common path.

---

## 14. Contracts — Events, APIs, Data Model, Ports

### 14.1 Events (Kafka — `oneday.hub.events`, `KafkaTopics.HUB_EVENTS`)

Discriminated by `common.kafka.enums.HubEventType`. **Existing:** `STAND_ASSIGNED`, `BAG_CREATED`, `SAMECITY_OUTBOUND`, `DEST_SORT_COMPLETE`, `DROP_VAN_HANDOFF` (now **deprecated**, `M7-D-004`). **To add:**

```
HubEventType (additions):
  PARCEL_SORTED_FOR_DELIVERY   per-parcel; → M6 binds to loop (M7-D-002)   { parcelId, cityId, destinationHexId, validDate, sortedAt, slaDeadline }
  BAG_SEALED                   bag frozen + manifest generated → M9, M10    { bagId, flightNo, flightDate, standNo, parcelCount, weight }
  MANIFEST_GENERATED           system manifest ready → M9 handover          { bagId, manifestId, flightNo }
  BAG_RESCHEDULED              low-weight/forced move to later flight (§9,§10) → M9, M10
  HUB_OVERLOAD_ALERT           wave/stand overload → M10, station mgr (M7-D-007)
  HUB_DISCREPANCY              dock reconciliation mismatch → M11, M10
```

**Consumed by M7:** `oneday.scan.events` (M8: `SELF_DROP_ACCEPTED`, `HUB_DEST_IN`, `GHA_ACCEPTANCE`, `HUB_COLLECT_COMPLETED`); `oneday.flight.events` (M9: `DELAYED`, `DEPARTED`, `LANDED`, `CANCELLED`); `oneday.shipments.events` (M4: `AT_ORIGIN_HUB` / `AT_DEST_HUB` state-changes as the sort trigger, and `CANCELLED` to pull a parcel from an open bag). Consumers dormant (`autoStartup=false`) until producers live, per the event-bus convention.

> `PARCEL_SORTED_FOR_DELIVERY` deliberately matches the field shape of M6's provisional `routing.events.payload.ParcelSortedForDeliveryEvent` so the stub→real swap is a no-op for M6 (`M7-D-002`).

### 14.2 REST API (operator console + queries)

```
# receiving
POST /hub/{hubId}/receive            dock scan-in (mode=VAN|SELF_DROP|AIRPORT); reconcile + start sort
POST /hub/{hubId}/receive/batch      bulk dock scan (scan-gun, ≤100)
# sortation / stands
GET  /hub/{hubId}/sort-plan?direction=&date=     current directory (sort_key→stand)
POST /hub/{hubId}/parcels/{parcelId}/resolve     stand + flight for a scanned parcel
# bags
POST /hub/{hubId}/bags                            open/get bag for a flight
POST /hub/{hubId}/bags/{bagId}/add                add parcel
POST /hub/{hubId}/bags/{bagId}/reassign-stand     stand-full move (relabel) (M7-D-008)
POST /hub/{hubId}/bags/{bagId}/seal               seal → manifest → BAG_SEALED
POST /hub/{hubId}/bags/{bagId}/reschedule         SLA-gated next-flight move (§9)
GET  /hub/{hubId}/bags/{bagId}/manifest           system manifest (M9 handover)
# inbound / delivery staging
POST /hub/{hubId}/inbound/break-bag               break landed bag, sort to delivery stands
GET  /hub/{hubId}/staging?loop=                   what's staged for which drop-van loop (M6/ops)
# ops
GET  /hub/{hubId}/load                            live overload snapshot (M10/ops)
```
City-scoped auth (M1): hub operator (own hub), station manager (own city), admin (any).

### 14.3 Data model (Flyway `V7_*`)

```
sort_plan              id, city_id, hub_id, direction(OUTBOUND|INBOUND), version,
                       active(bool), source(CONFIG|FLIGHT_UPDATE|MANUAL), created_at      [append-only revisions]
sort_plan_entry        id, sort_plan_id, sort_key, stand_id                               [the directory rows]
stand                  id, city_id, hub_id, stand_no, zone, kind(FLIGHT_BAG|DELIVERY_STAGING),
                       capacity, status(OPEN|FULL|CLOSED)
flight_bag             id, city_id, hub_id, flight_no, flight_date, origin_hub, dest_hub,
                       current_stand_id, status(OPEN|SEALED|DISPATCHED|HANDED_OVER),
                       parcel_count, weight_grams, manifest_id, sealed_at, dispatched_at    [status-mutable; contents append-only]
bag_item               id, bag_id, parcel_id, added_at, status(IN_BAG|REMOVED)             [append-only history]
bag_manifest           id, bag_id, flight_no, generated_at, parcels(jsonb), supersedes_id  [append-only, M7-D-008]
stand_reassignment_audit id, bag_id, old_stand_id, new_stand_id, actor_id, reason, created_at [append-only]
inbound_receipt        id, parcel_id, city_id, hub_id, arrival_mode(VAN|SELF_DROP|AIRPORT),
                       direction, reconciled(bool), discrepancy_type, received_at          [append-only]
delivery_staging       id, parcel_id, city_id, hub_id, dest_hex_id, stand_id, drop_type,
                       loop_hint, staged_at, status(STAGED|HANDED_TO_VAN|ON_SHELF|COLLECTED)
hub_load_snapshot      id, city_id, hub_id, wave_key, inbound_count, awaiting_sort,
                       stand_occupancy_pct, projected_clear_at, overloaded(bool), snapshot_at [overwritten/rolling]
```
Scans live in **M8's ledger** (referenced by `parcel_id` + scan point), not duplicated here.

### 14.4 Ports (unbuilt-dependency seams)

| Port | Backs | Stub until |
|------|-------|-----------|
| `FlightAssignmentPort` | M9 — assigned flight + cutoff for (dest, ready-time); next-flight-after | M9 (`M7-D-009`) |
| `BarcodePort` | M8 — build bag QR label, latest-scan lookup | M8 (`M7-D-010`) |
| `ShipmentInfoPort` (or reuse M4 service iface) | M4 — confirmed weight, SLA/commitment, drop type | M4 is built — wire real |

And the **inverse**: M7 *implements* M6's expectation by producing `PARCEL_SORTED_FOR_DELIVERY` (the real backing for `routing.service.port.HubSortPort`, `M7-D-002`).

---

## 15. Constraint Catalogue

| # | Constraint | Hard/Soft | Notes |
|---|-----------|-----------|-------|
| C1 | Every received parcel resolves to a stand or escalates | Hard | directory sort §7.1/§8.1 |
| C2 | Symmetric engine — one sort model both directions | Hard | M7-D-001 |
| C3 | Self-describing barcode drives the hot scan path | Hard | M7-D-003 |
| C4 | One scan per physical transfer — no double-scan | Hard | M7-D-005 |
| C5 | M7 stages, M6 loads — `HANDED_TO_DROP_VAN` is M6's scan | Hard | M7-D-004 |
| C6 | Flight bag groups one (flight, date, dest_hub) | Hard | §7.2 |
| C7 | Bag QR carries dual number — flight + physical stand | Hard | PRD §10.2, M8 |
| C8 | Manifest is system-generated at seal, append-only | Hard | NFR-1, M7-D-008 |
| C9 | Stand overflow → reassign + relabel, never lose the bag | Hard | M7-D-008, H2 |
| C10 | Reschedule only if **every** bag parcel still meets SLA | Hard | M7-D-006, A4 |
| C11 | Overload never drops SLA — flag + back-pressure + escalate | Hard | M7-D-007, H3 |
| C12 | Custody continuity — parcel never "nowhere" in the hub | Hard | §6 |
| C13 | Dock reconciliation — expected = actual or discrepancy | Hard | §6, mirrors M6-D-018 |
| C14 | Same-city skips the flight path entirely | Hard | §12 |
| C15 | City scoping — all data carries `city_id`/`hub_id` | Hard | NFR-5, M1 |
| C16 | Append-only — bags' contents, manifests, reassignments, receipts | Hard | NFR-1/2 |

---

## 16. Open Questions

| # | Question | Why it matters | Leaning |
|---|----------|----------------|---------|
| Q1 | Underweight threshold for bag reschedule — per airline? per route? who sets it? | gates §9 (A4) | config per (origin,dest,airline); ops-set; default flag-only |
| Q2 | End-to-end timing budget split: `cutoff − (sort + bag + shuttle)` (origin) and `landed → delivered` tail (dest) — who owns each slice? | sets first-mile cutoff-derived deadlines (M6 C10) and the §9 reschedule maths | **define jointly with M6, M9, M10** — same Q2 M6 flagged |
| Q3 | Label/QR standard — Code-128 vs PDF417 vs QR; do airline scanners need DataMatrix/IATA? | bag label print + airport scan (H1, M8 OD-M8-4) | QR for ours; validate IATA with M9/GHA before scale |
| Q4 | Stand-full reassign — reprint vs electronic-only update? (H2) | dual-number sync (M7-D-008) | reprint new label; electronic pointer + append-only audit |
| Q5 | Confirm the `DROP_VAN_HANDOFF` deprecation with M5 + M6 owners | resolves Conflict 1 (§13) | M7-D-004 — M6 owns the load |
| Q6 | Confirm single-scan rule (`VAN_UNLOAD` = hub in-scan) with M8 | resolves Conflict 2 (§13) | M7-D-005 — one scan |
| Q7 | Overload back-pressure — does M4 honour a throttle signal in v1, or is it alert-only? | H3 ops playbook | alert + advisory signal v1; real throttle policy deferred to ops |
| Q8 | Is there one hub per city always, or staging/secondary hubs? | hub_id cardinality | one primary hub/city v1 (PRD §6); model allows N |
| Q9 | Wave definition — fixed clock waves or pure per-flight-cutoff? | §7.4 sort timing | per-flight-cutoff v1; clock waves if volume needs batching |
| Q10 | Who computes `dest_hex` at the dest hub — M7 calls M3, or it's on the label? | §8.1 hot path | on the label (`sort_key`) + M3 fallback; cache |

---

## 17. Phase Plan

> Detailed PR breakdown goes in `docs/M7/M7-IMPLEMENTATION-PLAN.md` (to write next, mirroring M6's plan). Sketch:

| Phase | Deliverable |
|-------|-------------|
| **P1** | Skeleton; `HubEventType` additions + payloads in `common`; injectable `Clock`; Flyway `V7_*` (§14.3); seed stands + starter `sort_plan` for 5 hubs; stub ports (`FlightAssignmentPort`, `BarcodePort`). |
| **P2** | Inbound receiving (§6): dock scan-in (3 modes) + reconciliation; `inbound_receipt`; wire M4 `AT_ORIGIN_HUB`/`AT_DEST_HUB` triggers. |
| **P3** | Directory sort (§7.1/§8.1): `sort_plan` resolution; `STAND_ASSIGNED`; processing-state transitions. |
| **P4** | Flight bags (§7.2–7.3): create/add/seal, weight accumulation, `BAG_CREATED`/`BAG_SEALED`; stand-overflow reassign + relabel (M8). |
| **P5** | Manifest generation (§7.3): `bag_manifest` + `MANIFEST_GENERATED`; M9 handover artefact. |
| **P6** | **Inbound delivery sort (§8)** — the M6 seam: `PARCEL_SORTED_FOR_DELIVERY` (`M7-D-002`) + `DEST_SORT_COMPLETE`; hub-collect shelf + `AWAITING_HUB_COLLECT`. Swap M6's `BufferedHubSortPort` for the real feed. |
| **P7** | Same-city shortcut (§12); `SAMECITY_OUTBOUND`. |
| **P8** | Reschedule (§9) + flight-status reactions (§10): SLA-gated next-flight move; consume `oneday.flight.events` (against M9 stub). |
| **P9** | Overload back-pressure (§11): `hub_load_snapshot`, `HUB_OVERLOAD_ALERT`, throttle signal. |
| **P10** | Operator console API (§14.2) end-to-end; integration test with mock M6/M8/M9 consumers; virtual-hub simulation (§18). |

---

## 18. Testing & Simulation

Same philosophy as M6 (§21): a **synthetic world** of fake producers talking to the real module over the same Kafka/HTTP, an **injectable clock** so a hub day runs in seconds.

**Virtual hub (test-only).** Reads a published sort plan; consumes synthetic `VAN_UNLOAD` / `SELF_DROP_ACCEPTED` / `HUB_DEST_IN` arrivals; drives receive → sort → bag → seal via the real API; asserts the outputs. It pairs with M6's **virtual van** — M6's virtual van unloads collections that M7's virtual hub sorts; M7's `PARCEL_SORTED_FOR_DELIVERY` feeds M6's binder. The two simulators together exercise the full custody loop with no real hardware.

**Test pyramid.** Unit (directory resolution, peak stand occupancy, reschedule SLA maths, weight accumulation) → Contract (event schemas — especially `PARCEL_SORTED_FOR_DELIVERY` against M6's consumer; Testcontainers Kafka+Postgres) → Integration (boot monolith, synthetic arrivals, assert bag/manifest rows + M4 state) → **Simulation ("virtual hub day")** (seed plan+stands; N synthetic arrivals across waves; accelerated clock; assert invariants: every parcel stands-or-escalates C1, custody continuity C12, no SLA silently dropped C11, reschedule never breaches C10, same-city skips flight C14).

**Fault library.** Stand full → reassign + relabel · mis-sort (extra at dock) → `HUB_DISCREPANCY` → M11 · underweight bag → reschedule if SLA-safe else hold · flight cancelled → forced re-bag + SLA recheck · arrival spike → `HUB_OVERLOAD_ALERT` + throttle · landed bag mismatch → reconciliation discrepancy.

**Rollout ladder.** Simulation → shadow (real sort plan on live arrivals, output logged not acted on) → canary (one city/hub — design is city-scoped) → full. Kafka replay enables deterministic before/after on a recorded hub day.

---

*Draft v0.1. M7 owns the fixed cross-dock: inbound receive + sort (both directions), stand/bag/manifest, SLA-gated reschedule, flight-status reactions, overload back-pressure, and the hub↔van staging seam. Decisions M7-D-001…011, constraints C1…C16, open questions Q1…Q10. Sharpest items for next review: the end-to-end timing budget (Q2 — shared with M6/M9/M10), the two custody-boundary conflict resolutions (§13, Q5/Q6), and the overload/throttle policy (Q7, H3).*
