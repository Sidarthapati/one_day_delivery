# M7 — Hub Operations & Sortation: Design Doc

| Field | Value |
|-------|-------|
| **Module** | M7 — Hub Operations & Sortation (`hub`, `com.oneday.hub`) |
| **Status** | Draft v0.1 — full hub lifecycle (inbound receive → sort → stand → bag → manifest → dispatch), both directions |
| **Depends on** | `common` (event contracts, `BaseEntity`, `ShipmentState`), `barcode` / M8 (scan ledger + bag QR), `orders` / M4 (shipment record, confirmed weight, SLA) |
| **Consumed by** | M6 (sorted-for-delivery → loop binding; receives van unload), M8 (bag labels), M9 (flight bag + manifest handover), M4 (hub state transitions), M10 (per-leg SLA timestamps), M11 (mis-sort / overload exceptions) |
| **Source** | `docs/PRD-ONE-DAY-DELIVERY.md` §9, §10, §20 (H1–H3, A4) · `docs/MODULES.md` M7 · `docs/M6/M6-ROUTING-DESIGN.md` §16 (custody boundary) · `docs/M8-BARCODE-DESIGN.md` |

> **What M7 is, in one line.** The hub is the **fixed cross-dock** — the counterpart to M6's mobile cross-dock van. Everything that physically rests inside a city's hub between the first-mile and the last-mile passes through M7: it receives parcels at the dock, reads their barcode, decides the **stand** they go to, builds the **flight bag** (origin) or the **delivery/route bag** (destination), generates the **manifest**, and releases the bag to the airport shuttle (M9) or the parcels to the drop van (M6). M7 owns *everything inside the hub walls*.

> **How to read this doc.** Four parts.
> **Part 0 — Physical reality:** §0. **Read this first.** One parcel's whole physical journey — the QR sticker, the scan guns, the shelves, the bag — in plain language, so every system term in the rest of the doc has a real-world picture behind it.
> **Part I — What & boundaries:** §1–§4. What M7 does, what it owns, and the design decisions.
> **Part II — The sort engine:** §5–§12. The two directions, receiving, stand/bag/manifest, reschedule, flight reactions, overload, same-city shortcut.
> **Part III — Integration & governance:** §13–§18. Cross-module RACI (where this doc resolves two live M5↔M6↔M7 contract conflicts), contracts, constraints, open questions, phase plan, testing.
> Design decisions are tagged `M7-D-xxx`; constraints `Cn`; open questions `Qn`.

---

## Table of Contents

**Part 0 — Physical reality (read this first)**
0. [The Physical Reality — One Parcel, End to End](#0-the-physical-reality--one-parcel-end-to-end)

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

# Part 0 — Physical reality

## 0. The Physical Reality — One Parcel, End to End

> Read this before anything else. The rest of the doc uses system words (`stand`, `bag`,
> `manifest`). Every one of them is a **physical thing**. There is **no rule sheet** — the open bag
> itself is the directory (`M7-D-001`). This section is
> the physical picture; the rest is how we model it. Nothing in this system is more exotic than
> **"point a scanner at a sticker → an app makes an HTTP call."**

### 0.1 The one primitive the whole platform runs on

A parcel's QR sticker carries exactly **one thing: its ID string** (e.g. `BLR-20260627-000042`), plus a
little routing data (dest city/pincode). **No status, no history lives on the sticker** — all of that
lives in the database. Every physical event in the parcel's life is the same move:

```
   someone points a scanner at the sticker
        → the app decodes the ID string
        → the app makes an HTTP call: "parcel X — this just happened — by whom, where, when"
        → M8 records it in the append-only ledger
        → M4 advances the shipment's state
```

The **scanner is always dumb.** It is either a **phone camera** (DA app, van-driver app — the camera
*is* the scanner) or a **scan gun** at the hub (a Bluetooth/USB barcode reader on a tablet/PC running
the hub-operator web console, or a rugged Android scanner like a Zebra running the hub app). In every
case the scanner just turns the sticker into a string and hands it to an app; **the app makes the
call.** That is the entire integration surface — there is no magic hardware anywhere.

### 0.2 Where the QR comes from — there is no "QR machine"

Three plain steps, no special equipment:

1. **Mint the ID — software, at booking (M4).** When the customer books, M4 generates the ID string
   and reserves it in the DB. At this point the parcel is just a database row; there is no physical
   anything yet.
2. **Draw the QR — software, on demand (M8).** A QR is just an image that encodes the ID string. A
   library (ZXing) draws it: string in → PNG out. Pure function, no hardware.
3. **Print + stick — at pickup, by the DA.** The DA reaches the customer's door, opens the parcel in
   the DA app, taps **"print label."** The phone sends the label over Bluetooth to a **small pocket
   thermal printer** the DA carries (the same kind Delhivery / Ecom Express riders use). A sticker
   comes out; the DA peels it and sticks it on the box. The printer is dumb — it prints whatever the
   app sends. *(B2B variant: a business customer booking via API gets the label PDF back and prints it
   on their own warehouse printer before the DA arrives — same sticker, printed earlier.)*

The **very first scan** happens right here: the DA scans the sticker they just stuck on
(`DA_PICKUP_ATTACH`). From that instant the physical box **is** ID `BLR-...042` for the rest of its
life. *(Label-attachment mechanism is tracked as `Q11` — DA pocket-printer is the working assumption.)*

### 0.3 The plain-English glossary (system word → physical thing)

| System word in this doc | What it physically is |
|-------------------------|-----------------------|
| **Scan / scan event** | Someone points a scanner at the sticker; the app fires one HTTP call. |
| **Stand** | A **numbered spot on the hub floor / a labelled shelf or cage.** "Stand A-12" = the place where the Mumbai-flight parcels pile up. Real, physical. |
| **Open bag = the live directory** | There is **no fixed rule sheet.** A stand becomes "the Mumbai bag" only when the first Mumbai parcel opens a bag there; the app then remembers "Mumbai's open bag is on A-12" and sends the next Mumbai box to the same stand. When the bag flies out, A-12 is free for whatever opens there next. Dynamic assignment (`M7-D-001`) — the open `flight_bag` / `delivery_bag` row *is* the directory. |
| **Sortation / "to sort"** | The physical act of **separating the incoming jumble by destination** and walking each box to its stand. |
| **Flight bag** | A **literal bag / sack / cage.** All the Mumbai boxes go into one so we hand the airline **one unit, not 34 loose boxes.** It gets its own QR (flight number + which stand it's on). |
| **Manifest** | The **packing list** — "this bag contains these 34 parcel IDs." Handed to the airline so both sides agree what's inside. "System-generated" just means the software builds the list automatically from the scans, instead of someone writing it by hand. |
| **Wave / cutoff** | A **deadline batch**: "everything for the 6pm flight must be bagged by 5:30." Just the clock. |
| **Dock / receiving** | The **doorway of the hub** where vans unload and the operator scans boxes in. |
| **Delivery / route bag** | At the destination hub, the **bag/cage on a shelf where outbound boxes for one delivery route pile up** until the delivery van loads the whole bag — the mirror image of a flight bag. (If no van runs, it's a per-DA-territory bag the DA hub-collects.) |

### 0.4 One parcel, every physical touch — Bengaluru → Mumbai

Each row is the *same* primitive (§0.1). The "module" column shows who owns that step; **steps 3–9 are
M7, the hub.**

| # | Where, physically | Who scans, on what | What the app says (the API call) | DB state result | Module |
|---|-------------------|--------------------|----------------------------------|-----------------|--------|
| 1 | Customer's door, BLR | DA — phone; prints & sticks the label, scans it | "label attached, I have the box" | ID ↔ box bound; `PICKED_UP` | M5/M8 |
| 2 | Street corner — DA meets the consolidation van | DA phone **and** van-driver phone scan the box | "DA handed this box to the van" | `HANDED_TO_PICKUP_VAN` | M6 |
| 3 | Van pulls into the BLR hub; box comes off at the dock | Hub operator — **scan gun** at the counter | "this box is in the hub now" | `AT_ORIGIN_HUB` ← *the picture you had* | **M7** |
| 4 | Operator scans again to find out where it goes | scan gun | screen replies: *"Mumbai 6pm flight → Stand A-12"* (the open Mumbai bag's stand) | `ORIGIN_HUB_PROCESSING` | **M7** |
| 5 | Operator carries it to Stand A-12 (the Mumbai bag) and scans it into the bag | scan gun | "box is now in the Mumbai flight bag" | `IN_TAKEOFF_BAG` | **M7** |
| 6 | 6pm cutoff — operator closes the Mumbai bag | scan gun, taps "seal" | app **prints the bag's QR + the packing list (manifest)** | bag `SEALED`; manifest created | **M7** |
| 7 | Bag rides the airport shuttle; handed to the airline | airport handheld scans the **bag** QR | "airline has taken the bag" | `DISPATCHED_TO_AIRPORT` → handed over | M6/M9 |
| 8 | Flight lands in BOM; shuttle brings the bag to the BOM hub; operator opens it, scans each box | BOM hub scan gun | "arrived at Mumbai hub" | `AT_DEST_HUB` | **M7** |
| 9 | Operator scans to find out where it goes now | scan gun | *"Andheri territory → South delivery route → Stand D-3"* (the open South-route bag) | `DEST_HUB_PROCESSING` | **M7** |
| 10 | Box staged on D-3; delivery van loads it; delivery DA collects & delivers | van + DA phones scan along the way | the delivery handoffs | … → `DELIVERED` | M6/M5 |

**Reading the table:** the QR is created once (step 1) and never changes. Everything after is the same
scan→call→state-advance loop. M7 (steps 3–9) is exactly what you pictured — a person with a scan gun
at a counter, plus the shelves and bags the boxes move between, plus the software that (a) tells the
operator which shelf, and (b) prints the bag's packing list when the bag is sealed.

### 0.5 The hub floor, drawn

```
        ┌──────────────────────────── BLR HUB ────────────────────────────┐
        │                                                                  │
  van ─►│  DOCK ──scan in──►  [ jumbled pile ]                             │
 unload │  (operator,                  │  operator scans each box,         │
        │   scan gun)                  │  app says "Stand A-12 / A-13…"     │
        │                              ▼                                   │
        │      Stand A-12          Stand A-13          Stand A-14          │
        │   [ Mumbai 6pm bag ]  [ Delhi 7pm bag ]  [ Hyd 8pm bag ]        │
        │        │ seal @ cutoff → print bag QR + manifest                 │
        │        ▼                                                         │
        │   airport shuttle ──► airline ──► flight                        │──► (M9)
        └──────────────────────────────────────────────────────────────────┘

   Destination hub = the same floor, run in reverse: bag lands → break it →
   scan each box → "Stand D-3 (south drop van)" → delivery van loads → last mile.
```

That picture **is** M7. The rest of this document is how we model that floor, those rule sheets, those
bags, and those packing lists in code — and how it talks to the van (M6), the barcode ledger (M8), the
airline (M9), and the order state machine (M4). Whenever a later section says `stand`, `bag`, or
`manifest`, picture the shelf, the sack on it, and the packing list from this section. There is **no
rule sheet** — the open bag itself is the directory (`M7-D-001`).

---

# Part I — What & boundaries

## 1. What M7 Does

M7 governs all physical-and-digital operations **inside a city hub**. A parcel that touches a hub touches M7 twice in its life:

- At the **origin hub** (outbound), where a first-mile parcel is consolidated for its flight: receive → sort by destination/flight → flight bag → stand → manifest → dispatch to airport.
- At the **destination hub** (inbound), where a landed parcel is broken down for last-mile: receive → sort by `hex → DA territory → delivery route` → route bag → drop van loads the whole bag (M6), or hold for hub-collect.

These are the **same engine** run with two different sort keys (`M7-D-001`). The hub is symmetric (PRD §4.4, §9.4): origin consolidates *toward a flight*, destination de-consolidates *toward a delivery route*. One dynamic-bag abstraction, one stand model, one manifest model — parametrised by direction.

M7 is **workflow-heavy for human operators** (the hub operator scans, places, seals) and an **event consumer + producer**: it reacts to M8 scan events (parcel arrived) and M9 flight events (cutoff shifted / flight cancelled), and it emits the hub events M6/M9/M4/M10 react to.

M7 produces, per city, per hub, per day: a stand assignment for every received parcel; flight bags grouped by flight with system-generated manifests; a stream of "sorted for delivery" parcels that M6 binds to drop-van loops; bag reschedule decisions; and hub-overload back-pressure signals.

---

## 2. Scope & Ownership

### 2.1 In scope — M7 owns all of this

| Area | What M7 owns |
|------|--------------|
| **Inbound receiving** | The dock: accepting parcels from three arrival modes (van unload, self-drop, airport shuttle), reconciling against expectation, taking hub custody. |
| **Sortation** | Resolving each parcel's stand **dynamically** from the open consolidation unit for its key — origin: `dest_hub + flight`; destination: the `dest_hex → DA territory → delivery route` ladder (`M7-D-012`). No static directory; the open bag *is* the directory (`M7-D-001`). |
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
- **No hub physical model exists today.** No stand, no flight bag, no delivery bag, no manifest entity exists anywhere in the repo. M7 introduces all of them (no static directory — the open bag is the directory, `M7-D-001`).

---

## 3. Industry Context — How Sortation Is Actually Solved

A hub is a **cross-dock**: goods arrive, are re-sorted by outbound destination, and leave — ideally with minimal dwell and no long-term storage. The sortation core is not an optimisation problem like M6's VRP; it is a **deterministic routing-table lookup** (`sort_key → outbound lane`) wrapped in **physical capacity management** and **wave/batch timing**.

| Our concept | Industry term | What it is |
|-------------|---------------|-----------|
| Stand | **Sort destination / chute / pigeonhole** | The physical lane a parcel is directed to. In automated facilities this is a chute fed by a sorter belt; for us it is a numbered floor stand a human places on. |
| (no static directory) | **Sort directory / nixie chart** | The classic high-volume pattern is a fixed `destination → lane` directory. We deliberately don't keep one — the open bag is our directory, allocated on demand (`M7-D-001`). |
| Flight bag | **Container / ULD-equivalent / gaylord** | The consolidation unit grouped by next leg (flight). |
| Manifest | **Container manifest / BOL** | The system record of contents handed to the carrier. |
| Wave | **Sort wave / cut** | A timed batch of sorting aligned to outbound departures (flight cutoffs). |

**How the industry does it:** FedEx/UPS hubs run a fixed **directory sort** (barcode → chute) on high-speed sorters; the intelligence is in the *directory* (kept current with the flight/linehaul plan) and in **wave planning** (which parcels must clear the sort by which cutoff). Amazon sort centers run the same directory pattern with `sort_key → stack` and **rate/health monitoring** for overload. Our hub is the **manual, low-volume version**: a human scan gun resolves the stand from the barcode, and the "sorter" is the operator. That makes M7 a **CRUD-plus-workflow** service, not a solver — its hard parts are (a) resolving the right open bag as flights and routes change, (b) the bag-seal/manifest discipline, (c) the SLA-gated reschedule, and (d) graceful overload back-pressure.

**Our choice (`M7-D-001`):** a single direction-parametrised engine with **dynamic stand assignment** — the consolidation unit (flight bag / delivery bag) is allocated a free stand from the hub's physical pool when first opened, and the open unit is the live directory. No pre-seeded `destination → stand` mapping, no solver. (The classic automated-hub "directory sort" — a static `sort_key → chute` directory — is the high-volume pattern; for our manual low-volume hub dynamic allocation is simpler and never goes stale.) On the **destination** side the consolidation unit is a **route bag**: the parcel's `dest_hex → DA territory → delivery route (loop)` ladder (`M7-D-012`) picks the loop, the open route bag for that loop gets a free delivery stand, and a van loads the whole bag in one move. With no van service the hub falls back to a **DA-territory bag** the DA hub-collects — still dynamic, just keyed differently. Nothing on either side is pre-mapped. The reschedule check (§9) and overload back-pressure (§11) are the only non-trivial logic.

---

## 4. Key Design Decisions

### Engine & model

**M7-D-001 — One symmetric, direction-parametrised sort engine with *dynamic* stand assignment.** Origin (outbound, consolidate to flight bag) and destination (inbound, de-consolidate to delivery/route bag) run the same engine, differing only in the sort key (`dest_hub + flight` vs the `dest_hex → DA territory → delivery route` ladder, `M7-D-012`) and the consolidation unit (flight bag vs delivery/route bag). A parcel's stand is **not** read from a pre-seeded `destination → stand` directory. The stand is the stand of the **open consolidation unit** for that key — the flight bag (resp. delivery/route bag) — which is allocated a **free stand from the hub's physical pool when it is first opened**, and freed on dispatch/load. The open `flight_bag` / `delivery_bag` row *is* the live directory ("which stand is the Chennai bag on right now?" = "find the open Chennai bag → its `current_stand_id`"). Only the physical stand pool is seeded (real shelves), never the mapping. One `stand` pool (stands have **no flight/delivery `kind`** — a stand is a bare physical spot whose role is whatever bag currently sits on it), two mirrored consolidation tables (origin `flight_bag` + `bag_item`; destination `delivery_bag` + `delivery_bag_item`), one manifest model — the same dynamic pattern both directions. Resolves PRD §4.4 symmetry. *(Rationale: for a low-volume manual hub, dynamic allocation avoids a stale pre-mapped directory and buys nothing less — the operator scans every box and reads the stand off the screen regardless. A static directory is the high-volume automated-sorter pattern, deliberately not adopted here.)*

**M7-D-002 — M7 is the real backing for M6's `HubSortPort`.** The deliver-side feed M6 currently reads from its buffer stub is produced by M7: on destination-sort completion per parcel, M7 emits a per-parcel `PARCEL_SORTED_FOR_DELIVERY` hub event carrying `(parcelId, cityId, destinationHexId, validDate, sortedAt, slaDeadline)` — the exact shape of the provisional `ParcelSortedForDeliveryEvent` M6 already consumes. Building M7 swaps the stub for the real feed with **no change to M6's binder**.

**M7-D-003 — The destination is read from the barcode; the stand is assigned dynamically.** Per PRD §10, the parcel's barcode payload carries `dest_hub` / `sort_key`; the hub scan reads the **destination** off the self-describing label — no M4 round-trip for routing data on the hot scan path (the M8 contract guarantees it). The **stand** is then the open flight bag's stand for that `(flight, dest_hub)` — allocated dynamically from the hub's pool on first open (`M7-D-001`), not looked up in a pre-seeded directory. M7 *does* read M4 for confirmed weight (bag weight, §7.2/§9) and SLA (reschedule gate), off the hot path.

**M7-D-012 — Destination sort resolves by a `hex → territory → route` ladder; the delivery bag is a route bag (van) with a DA-territory bag fallback (no van).** At the destination hub a parcel's last-mile grouping is decided **dynamically**, never from a directory and never per-hex (a city has ~thousands of delivery hexes — far too many to stand). The ladder:
> 1. **`dest_hex`** — from the label `sort_key` (M3 serviceability fallback, off the hot path / cached).
> 2. **`dest_hex → DA territory`** — the grid (M3) owns which DA territory owns the hex. **Always resolvable** (every serviceable hex belongs to a territory). Port: `TerritoryPort`.
> 3. **`DA territory → delivery route (loop)`** — the nightly van route plan (M6) covers each territory with a delivery loop. **Available iff vans run** for that city/day. Port: `DeliveryRoutePort`.
>
> Then the consolidation unit:
> - **Route present → `ROUTE` bag**, keyed `(route_plan/loop, date)`. One bag per loop ⇒ **~one stand per loop** (tens, not thousands). A van loads the entire bag in one move. **Preferred** — fewest stands, zero re-handling, matches the van-as-mini-hub model.
> - **No route → `DA_TERRITORY` bag**, keyed `(da_territory, date)`. The DA comes to the hub and **hub-collects** their territory's bag. Needs only M3 (always present), so the hub is **fully functional with no van service at all** — at the cost of more stands (~one per active territory). If active territories exceed the delivery-stand pool, adjacent territories are grouped into a **zone bag** (M3 zone) to fit; if still short → overload back-pressure (§11). Never a silent drop.
>
> The open `delivery_bag` row *is* the dest directory, exactly as the open `flight_bag` is the origin directory (`M7-D-001`). *Why route-first:* the route bag is the unit a van actually loads, so it needs the fewest stands and no re-sorting; the territory fallback is the no-van safety net. *(M7 groups physically by route; M6 owns the van trip/capacity within a loop — see Q12.)*

### Custody boundary (the conflict resolutions — see §13)

**M7-D-004 — In the M6 model, M7 does NOT hand parcels to the drop van; it stages them. M6 loads.** The legacy M5 design (v1.1) has M7 emit `DROP_VAN_HANDOFF` to drive `HANDED_TO_DROP_VAN`. The newer M6 design makes the van a mini-hub that owns its own load: M6 binds the parcel to a loop and the hub operator records the `VAN_LOAD` scan (`M6-D-014`). **This doc adopts the M6 model.** M7's job ends at "sorted for delivery, staged on the delivery stand"; `HANDED_TO_DROP_VAN` is driven by M6's `VAN_LOAD` scan, not an M7 event. `HubEventType.DROP_VAN_HANDOFF` is therefore **deprecated** (kept in the enum for back-compat; not emitted by M7 v1). *To confirm with M5 + M6 owners (Q5).*

**M7-D-005 — The van-unload scan IS the hub in-scan; M7 does not double-scan.** M6 originates `VAN_UNLOAD` for first-mile collections arriving at the dock (`M6-D-014`), which already drives `HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB`. M8's `HUB_ORIGIN_IN` describes the *same* physical transition. **One scan, not two:** for van arrivals M7 begins sortation off the `AT_ORIGIN_HUB` state (consumed from M4 / the scan ledger), and reserves `HUB_ORIGIN_IN`/`SELF_DROP_ACCEPTED` for **non-van** arrivals (self-drop). This avoids a duplicate ledger entry for the most common path. *Confirm the single-scan rule with M8 (Q6).* **Corollary — the arrival mode is derived, not input:** since the dock already reads the parcel's M4 state, M7 *derives* VAN / SELF_DROP / AIRPORT from that state (`HANDED_TO_PICKUP_VAN`/`AT_ORIGIN_HUB` → VAN, `AWAITING_SELF_DROP` → SELF_DROP, `LANDED`/`DISPATCHED_TO_HUB`/`AT_DEST_HUB` → AIRPORT) rather than reading it off the barcode or asking the operator. The `receive` API carries no `mode` field.

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
   landed in   ──►  DOCK ──► SORT(hex→territory→route) ──► ROUTE BAG ──► drop van loads whole bag (M6)
   (airport shuttle)                                       (DA-territory bag if no van)  OR ──► HUB-COLLECT shelf
```

**The two sort keys (`M7-D-001`):**

| Direction | Trigger state (M4) | Sort key | Consolidation unit | Exit |
|-----------|--------------------|----------|--------------------|------|
| **OUTBOUND** (origin) | `AT_ORIGIN_HUB` | `dest_hub + assigned_flight` | flight bag → stand | `IN_TAKEOFF_BAG` → `DISPATCHED_TO_AIRPORT` (shuttle) |
| **INBOUND** (dest) | `AT_DEST_HUB` | `dest_hex → DA territory → delivery route` (`M7-D-012`) | **route bag** (per loop; DA-territory bag if no van) | `HANDED_TO_DROP_VAN` (M6 loads) or `AWAITING_HUB_COLLECT` |

The state names already exist in `common.domain.enums.ShipmentState` — M7 aligns to them. The hub spans these M4 states:

```
 AT_ORIGIN_HUB → ORIGIN_HUB_PROCESSING → IN_TAKEOFF_BAG → DISPATCHED_TO_AIRPORT   (outbound)
 AT_DEST_HUB   → DEST_HUB_PROCESSING   → HANDED_TO_DROP_VAN | AWAITING_HUB_COLLECT  (inbound)
```

M7 drives the **processing** transitions (`*_HUB_PROCESSING`, `IN_TAKEOFF_BAG`, `DEST_SORT_COMPLETE`); the *exit* transitions are driven by the next owner's scan (shuttle dispatch → M6/M9; `VAN_LOAD` → M6; hub-collect → M8 `HUB_COLLECT_COMPLETED`).

---

## 6. Inbound Receiving — Three Arrival Modes

A parcel enters the hub by one of three routes. M7's dock reconciles each against an expectation and takes custody.

> **The arrival mode is derived, never declared (`M7-D-005`).** The barcode is dumb — it carries the parcel ID + routing data, *not* how it arrived. The operator doesn't pick a mode either. M7 **derives** the mode from the parcel's current M4 state (the leg it just finished), which it already looks up for weight: `HANDED_TO_PICKUP_VAN` (or `AT_ORIGIN_HUB`, if M6 already in-scanned it) → **VAN**; `AWAITING_SELF_DROP` → **SELF_DROP**; `LANDED` / `DISPATCHED_TO_HUB` / `AT_DEST_HUB` → **AIRPORT**. These prior states are mutually exclusive, so the derivation is unambiguous; the scan *station* (van dock vs counter vs break-bag bench) is at most a corroborating hint for reconciliation.

| Mode (derived) | Source | Scan / signal | M4 result | M7 action |
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
3. **Open (or find) the flight bag** for `(flight, date, dest_hub)`. On first open the bag is allocated a **free stand from the hub's shared stand pool** (dynamic assignment, `M7-D-001`; the pool has no flight/delivery kind — `zone` is a soft preference so flight bags fill the airport-dock shelves first); subsequent parcels for the same flight reuse that bag and its stand. The bag's stand is the parcel's stand. Emit `STAND_ASSIGNED` (→ operator console, M8 if relabel needed). Transition `→ ORIGIN_HUB_PROCESSING`.

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

## 8. Inbound Sort — Route Bag, DA-Territory Fallback & Hub-Collect

The destination-hub path: break a landed parcel down toward last-mile (mirror of §7). Stand assignment is **dynamic** exactly as origin — but the sort key is resolved by a ladder (`M7-D-012`), and the consolidation unit is a **route bag** (the unit a delivery van loads in one move) with a **DA-territory bag** fallback when no van runs.

**8.1 Break the bag, resolve the delivery route.** On `AT_DEST_HUB` (`HUB_DEST_IN`):
1. Reconcile the landed bag against its M9 manifest (§6).
2. Per parcel, run the resolution ladder (`M7-D-012`):
   - `dest_hex` ← label `sort_key` (M3 serviceability fallback, off the hot path / cached).
   - `dest_hex → DA territory` ← `TerritoryPort` (M3) — always resolvable.
   - `DA territory → delivery route (loop)` ← `DeliveryRoutePort` (M6's published nightly plan) — empty if no van runs that city/day.
3. **Open (or find) the delivery bag** for the resolved key and stage the parcel into it — a free stand is allocated from the same shared pool on first open (dynamic, `M7-D-001`; `zone` preference fills the delivery-dock shelves first):
   - **Route present → `ROUTE` bag**, keyed `(route_plan/loop, date)`. ~One stand per loop; a van loads the whole bag. **Preferred.**
   - **No route → `DA_TERRITORY` bag**, keyed `(da_territory, date)`. The DA hub-collects their bag. If active territories exceed the delivery-stand pool, adjacent territories are grouped into a **zone bag** (M3 zone) to fit; if still short → overload (§11). Never per-hex.

   Transition `→ DEST_HUB_PROCESSING`.

**8.2 Branch on drop type.** From the shipment record (M4):
- **`DA_DELIVERY`** → the parcel is staged in its route/territory bag and M7 emits **`PARCEL_SORTED_FOR_DELIVERY`** (`M7-D-002`) carrying the M6-binder shape `(parcelId, cityId, destinationHexId, validDate, sortedAt, slaDeadline)` **plus additive** `(daTerritoryId, routePlanId|loopId, deliveryBagId, standNo)`. M6 binds the parcel to the loop's van trip and later records `VAN_LOAD` (which drives `HANDED_TO_DROP_VAN`, `M7-D-004`); when the van comes for loop L it loads the whole route bag. M7 also emits `DEST_SORT_COMPLETE` per city/wave for M10. *(M7 groups physically by route; M6 owns the van trip/capacity within a loop — Q12.)*
- **`HUB_COLLECT`** → place on the **hub-collect shelf**, transition `→ AWAITING_HUB_COLLECT`, notify customer (via notification command). Customer arrival → M8 `HUB_COLLECT_COMPLETED` → `HUB_COLLECTED` (terminal). M7 owns the shelf location + shelf-occupancy, not the customer interaction.

**8.3 Why M7 groups but M6 loads.** The route bag is the *handoff buffer* between the fixed hub and the mobile van. M7 guarantees "the right parcels are grouped into the right route bag, on a stand"; M6 owns "which van trip, loaded when, in reverse stop order." M7 resolves the loop **read-only** from M6's published nightly plan (it does not schedule the van); the bag is the physical unit M6's `VAN_LOAD` consumes. This is the clean seam `M7-D-004` formalises and that M6's `HubSortPort` already expects — now upgraded from per-parcel staging to a whole-bag load.

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
| Dynamic bag→stand allocation | **Own** | — | — | feeds flight | — | — |
| Dest route resolution (hex→territory→route) | **Own** (resolve) | route plan (provide) | — | — | — | — |
| Stand / bag / manifest | **Own** | — | bag QR util | consume manifest | — | — |
| Flight assignment / cutoff | consume | consume | — | **Own** | — | — |
| "Sorted for delivery" feed | **Own** (produce) | consume (bind) | — | — | — | — |
| Van load (stand→van) | stage only | **Own** (`VAN_LOAD`) | record scan | — | transition | — |
| `HANDED_TO_DROP_VAN` trigger | ~~emit~~ → **M6's scan** | **Own** | record | — | transition | consume |
| Hub-collect shelf | **Own** | — | `HUB_COLLECT_COMPLETED` | — | transition | — |
| Reschedule decision | **Own** | — | — | re-handover | — | — |
| Overload back-pressure | **Own** | — | — | — | throttle target | — |
| SLA judgement | feed | feed | feed | feed | feed → **M10** | feed |

> Dest route resolution reads two built modules: M3 owns `hex → DA territory` (`TerritoryPort`); M6 owns `DA territory → delivery route/loop` from its nightly plan (`DeliveryRoutePort`). M7 only *reads* them to pick the bag — it never schedules the van (§8.3).

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
  PARCEL_SORTED_FOR_DELIVERY   per-parcel; → M6 binds to loop (M7-D-002)   { parcelId, cityId, destinationHexId, validDate, sortedAt, slaDeadline }  (+ additive: daTerritoryId, routePlanId|loopId, deliveryBagId, standNo)
  BAG_CREATED                  bag opened on a stand → operator/ops          { bagId, cityId, hubId, direction, ...flight or route/territory key..., standNo }
  BAG_SEALED                   bag frozen + manifest generated → M9, M10    { bagId, direction, ...key..., standNo, parcelCount, weight }
  MANIFEST_GENERATED           system manifest / load list ready            { bagId, manifestId, direction, ...key... }
  BAG_RESCHEDULED              low-weight/forced move to later flight (§9,§10) → M9, M10
  HUB_OVERLOAD_ALERT           wave/stand overload → M10, station mgr (M7-D-007)
  HUB_DISCREPANCY              dock reconciliation mismatch → M11, M10
```

> **Delivery (route/territory) bags reuse `BAG_CREATED` / `BAG_SEALED` / `MANIFEST_GENERATED` with `direction=INBOUND`** and the route/territory key in place of the flight fields (additive; tolerant readers ignore the unused side). No new enum values are needed for the dest bag lifecycle.

**Consumed by M7:** `oneday.scan.events` (M8: `SELF_DROP_ACCEPTED`, `HUB_DEST_IN`, `GHA_ACCEPTANCE`, `HUB_COLLECT_COMPLETED`); `oneday.flight.events` (M9: `DELAYED`, `DEPARTED`, `LANDED`, `CANCELLED`); `oneday.shipments.events` (M4: `AT_ORIGIN_HUB` / `AT_DEST_HUB` state-changes as the sort trigger, and `CANCELLED` to pull a parcel from an open bag). Consumers dormant (`autoStartup=false`) until producers live, per the event-bus convention.

> `PARCEL_SORTED_FOR_DELIVERY` deliberately matches the field shape of M6's provisional `routing.events.payload.ParcelSortedForDeliveryEvent` so the stub→real swap is a no-op for M6 (`M7-D-002`).

### 14.2 REST API (operator console + queries)

```
# receiving
POST /hub/{hubId}/receive            dock scan-in (mode=VAN|SELF_DROP|AIRPORT); reconcile + start sort
POST /hub/{hubId}/receive/batch      bulk dock scan (scan-gun, ≤100)
# sortation / stands
GET  /hub/{hubId}/bags?direction=&date=          live open bags = the dynamic directory (which stand holds which flight/route right now)
POST /hub/{hubId}/parcels/{parcelId}/resolve     resolve + open-bag stand for a scanned parcel
# bags
POST /hub/{hubId}/bags                            open/get bag for a flight
POST /hub/{hubId}/bags/{bagId}/add                add parcel
POST /hub/{hubId}/bags/{bagId}/reassign-stand     stand-full move (relabel) (M7-D-008)
POST /hub/{hubId}/bags/{bagId}/seal               seal → manifest → BAG_SEALED
POST /hub/{hubId}/bags/{bagId}/reschedule         SLA-gated next-flight move (§9)
GET  /hub/{hubId}/bags/{bagId}/manifest           system manifest (M9 handover)
# inbound / delivery staging
POST /hub/{hubId}/inbound/break-bag               break landed bag, sort each parcel to its route/territory bag
GET  /hub/{hubId}/delivery-bags?loop=&date=       route/territory bags + contents (what a van loads) (M6/ops)
GET  /hub/{hubId}/staging?loop=                   per-parcel staging view for a loop (M6/ops)
# ops
GET  /hub/{hubId}/load                            live overload snapshot (M10/ops)
```
City-scoped auth (M1): hub operator (own hub), station manager (own city), admin (any).

### 14.3 Data model (Flyway `V7_*`)

```
-- No sort_plan / sort_plan_entry: stand assignment is dynamic (M7-D-001). The physical stand pool
-- is the only seeded config. Origin uses flight_bag + bag_item; destination uses delivery_bag +
-- delivery_bag_item — same dynamic pattern, mirrored. The open flight_bag / delivery_bag row is the
-- live directory for its direction.
stand                  id, city_id, hub_id, stand_no, zone, capacity, status(OPEN|OCCUPIED|CLOSED)
                       -- NO kind: a stand is a bare physical spot; its role = whatever bag sits on it
                       -- (M7-D-001). zone is a soft floor-area hint (AIRPORT_DOCK | DELIVERY_DOCK).
-- ── origin (outbound) ──
flight_bag             id, city_id, hub_id, flight_no, flight_date, origin_hub, dest_hub,
                       current_stand_id, status(OPEN|SEALED|DISPATCHED|HANDED_OVER),
                       parcel_count, weight_grams, manifest_id, sealed_at, dispatched_at    [status-mutable; contents append-only]
bag_item               id, bag_id, parcel_id, added_at, status(IN_BAG|REMOVED)             [append-only membership of a flight_bag]
-- ── destination (inbound) — mirror of flight_bag/bag_item (M7-D-012) ──
delivery_bag           id, city_id, hub_id, bag_kind(ROUTE|DA_TERRITORY|ZONE), bag_date,
                       route_plan_id, loop_id, da_territory_id, zone_id, current_stand_id,
                       status(OPEN|SEALED|LOADED|HANDED_OVER), parcel_count, weight_grams,
                       manifest_id, sealed_at, loaded_at                                    [status-mutable; contents append-only]
delivery_bag_item      id, parcel_id, city_id, hub_id, dest_hex_id, da_territory_id, route_plan_id,
                       delivery_bag_id, stand_id, drop_type, staged_at,
                       status(STAGED|LOADED|ON_SHELF|COLLECTED)                              [membership of a delivery_bag — mirror of bag_item]
-- ── shared ──
bag_manifest           id, bag_id, direction, manifest_kind, generated_at, parcels(jsonb), supersedes_id  [append-only, M7-D-008; flight manifest or route load-list]
stand_reassignment_audit id, bag_id, old_stand_id, new_stand_id, actor_id, reason, created_at [append-only]
inbound_receipt        id, parcel_id, city_id, hub_id, arrival_mode(VAN|SELF_DROP|AIRPORT),
                       direction, reconciled(bool), discrepancy_type, received_at          [append-only]
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
| `TerritoryPort` | M3 — `dest_hex → DA territory (+zone)` for inbound sort (`M7-D-012`) | M3 is built — wire real |
| `DeliveryRoutePort` | M6 — `DA territory → delivery route/loop` from the nightly plan; **empty if no van** (`M7-D-012`) | M6 is built — wire real; stub for tests |

And the **inverse**: M7 *implements* M6's expectation by producing `PARCEL_SORTED_FOR_DELIVERY` (the real backing for `routing.service.port.HubSortPort`, `M7-D-002`).

---

## 15. Constraint Catalogue

| # | Constraint | Hard/Soft | Notes |
|---|-----------|-----------|-------|
| C1 | Every received parcel resolves to a stand or escalates | Hard | directory sort §7.1/§8.1 |
| C2 | Symmetric engine — one dynamic-bag model both directions | Hard | M7-D-001 |
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
| C17 | Dest sort resolves by the hex→territory→route ladder; route bag if a van runs, DA-territory bag otherwise — never per-hex, never a silent drop | Hard | M7-D-012 |

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
| Q11 | Label attachment mechanism — DA pocket thermal printer vs pre-printed (B2B warehouse) vs handwritten fallback? | §0.2; affects M8 + DA app, not M7 internals | DA Bluetooth thermal printer primary; B2B pre-print via API; confirm with ops/M5/M8 |
| Q12 | M7 groups physically by **route** (one bag/loop); M6 binds per-parcel and may split a loop across van trips by capacity. Reconcile route-bag granularity with M6's trip-level binding. | dest staging ↔ M6 load (§8.3, M7-D-012) | M7 bag = delivery route; M6 owns trip/capacity within it; confirm with M6 owner |
| Q13 | No-van fallback: if active DA territories exceed the delivery-stand pool, group adjacent territories into a zone bag (M3 zone) — what's the grouping rule and the pool size per hub? | §8.1 stand fit (M7-D-012) | zone-group by M3 adjacency to fit pool; size from peak territory count; ops-set |

---

## 17. Phase Plan

> Detailed PR breakdown goes in `docs/M7/M7-IMPLEMENTATION-PLAN.md` (to write next, mirroring M6's plan). Sketch:

| Phase | Deliverable |
|-------|-------------|
| **P1** | Skeleton; `HubEventType` additions + payloads in `common`; injectable `Clock`; Flyway `V7_*` (§14.3); seed the **physical stand pool** for 5 hubs (no directory, `M7-D-001`); stub ports (`FlightAssignmentPort`, `BarcodePort`). |
| **P2** | Inbound receiving (§6): dock scan-in (3 modes) + reconciliation; `inbound_receipt`; wire M4 `AT_ORIGIN_HUB`/`AT_DEST_HUB` triggers. |
| **P3** | Dynamic sort (§7.1/§8.1): resolve flight/route → open-bag stand (allocate on first open); `STAND_ASSIGNED`; processing-state transitions. |
| **P4** | Flight bags (§7.2–7.3): create/add/seal, weight accumulation, `BAG_CREATED`/`BAG_SEALED`; stand-overflow reassign + relabel (M8). |
| **P5** | Manifest generation (§7.3): `bag_manifest` + `MANIFEST_GENERATED`; M9 handover artefact. |
| **P6** | **Inbound delivery sort (§8, `M7-D-012`)** — the hex→territory→route ladder (`TerritoryPort`/`DeliveryRoutePort`); `delivery_bag` (route bag + DA-territory fallback); the M6 seam: `PARCEL_SORTED_FOR_DELIVERY` (`M7-D-002`) + `DEST_SORT_COMPLETE`; hub-collect shelf + `AWAITING_HUB_COLLECT`. Swap M6's `BufferedHubSortPort` for the real feed. |
| **P7** | Same-city shortcut (§12); `SAMECITY_OUTBOUND`. |
| **P8** | Reschedule (§9) + flight-status reactions (§10): SLA-gated next-flight move; consume `oneday.flight.events` (against M9 stub). |
| **P9** | Overload back-pressure (§11): `hub_load_snapshot`, `HUB_OVERLOAD_ALERT`, throttle signal. |
| **P10** | Operator console API (§14.2) end-to-end; integration test with mock M6/M8/M9 consumers; virtual-hub simulation (§18). |

---

## 18. Testing & Simulation

Same philosophy as M6 (§21): a **synthetic world** of fake producers talking to the real module over the same Kafka/HTTP, an **injectable clock** so a hub day runs in seconds.

**Virtual hub (test-only).** Seeds the stand pool; consumes synthetic `VAN_UNLOAD` / `SELF_DROP_ACCEPTED` / `HUB_DEST_IN` arrivals; drives receive → resolve (open bags dynamically) → bag → seal via the real API; asserts the outputs. It pairs with M6's **virtual van** — M6's virtual van unloads collections that M7's virtual hub sorts; M7's `PARCEL_SORTED_FOR_DELIVERY` feeds M6's binder. The two simulators together exercise the full custody loop with no real hardware.

**Test pyramid.** Unit (directory resolution, peak stand occupancy, reschedule SLA maths, weight accumulation) → Contract (event schemas — especially `PARCEL_SORTED_FOR_DELIVERY` against M6's consumer; Testcontainers Kafka+Postgres) → Integration (boot monolith, synthetic arrivals, assert bag/manifest rows + M4 state) → **Simulation ("virtual hub day")** (seed plan+stands; N synthetic arrivals across waves; accelerated clock; assert invariants: every parcel stands-or-escalates C1, custody continuity C12, no SLA silently dropped C11, reschedule never breaches C10, same-city skips flight C14).

**Fault library.** Stand full → reassign + relabel · mis-sort (extra at dock) → `HUB_DISCREPANCY` → M11 · underweight bag → reschedule if SLA-safe else hold · flight cancelled → forced re-bag + SLA recheck · arrival spike → `HUB_OVERLOAD_ALERT` + throttle · landed bag mismatch → reconciliation discrepancy.

**Rollout ladder.** Simulation → shadow (real dynamic sort on live arrivals, output logged not acted on) → canary (one city/hub — design is city-scoped) → full. Kafka replay enables deterministic before/after on a recorded hub day.

---

*Draft v0.2. M7 owns the fixed cross-dock: inbound receive + sort (both directions, **fully dynamic** — origin flight bags, destination route bags with a DA-territory fallback, `M7-D-001`/`M7-D-012`), stand/bag/manifest, SLA-gated reschedule, flight-status reactions, overload back-pressure, and the hub↔van staging seam. Decisions M7-D-001…012, constraints C1…C17, open questions Q1…Q13. Sharpest items for next review: the end-to-end timing budget (Q2 — shared with M6/M9/M10), the route-bag ↔ M6-trip-binding reconciliation (Q12), the two custody-boundary conflict resolutions (§13, Q5/Q6), and the overload/throttle policy (Q7, H3).*
