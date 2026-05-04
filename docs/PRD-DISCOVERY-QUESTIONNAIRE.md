# Requirements discovery — discussion questionnaire

**Purpose:** Use this in your call with your father (and in follow-up meetings) so answers feed directly into a **PRD** → **Design doc** → **PoC** sequence.

**How to use:** You do not need every answer in one night. Mark items as: **answered** / **TBD** / **decision needed**. Any **TBD** becomes an explicit work item for the next meeting.

---

## 1. Meeting intent (say this at the start)

- We are not locking tech yet; we are **locking what must be true in operations** and **which decisions the algorithms must make**.
- Output of this process: a **PRD** with **problem, scope, users, non-goals, metrics, and constraints**; then a **design doc** (algorithms, data, services); then a **PoC** on a thin slice (e.g. one city-pair, one grid).

---

## 2. Business, scope, and non-goals

| # | Question | Why it matters |
|---|------------|----------------|
| 2.1 | What is the **first** product: B2B only, B2C only, or both? | Changes UX, SLAs, and pricing. |
| 2.2 | What is **v1 geography**: one city, two hubs, a city-pair, or “pan-India from day one”? | Scope for grids, vans, and algorithms. |
| 2.3 | What is explicitly **out of scope** for v1 (e.g. international, returns, same-hour)? | Stops requirements creep. |
| 2.4 | Is revenue model fixed per shipment, per kg, per km, subscription, or **EaseMyTrip bundle**? | Affects what we optimize (cost vs speed vs margin). |
| 2.5 | What **margin or cost ceiling** is acceptable per parcel (e.g. max ₹X per leg)? | Direct input to **cost-efficiency** constraints in algorithms. |

---

## 3. Current and target operations (procedural — very important)

| # | Question | Why it matters |
|---|------------|----------------|
| 3.1 | Walk me through **one parcel end-to-end** on paper: order → pickup → first hub → air → second hub → last mile. Who touches it, what systems (if any), and **where** decisions happen today? | This is the backbone of the PRD. |
| 3.2 | For **v1**, which steps are **manual** (OK) vs **must be automated**? | Drives PoC scope. |
| 3.3 | What are **operating hours** for: customer booking, pickup, hubs, and airport handoff? | Defines **cutoff times** in ETA and routing. |
| 3.4 | **Peak** vs **normal** day: how many orders (order of magnitude) do we need to design for in year 1? | Load at hub, fleet sizing, grid sizes. |
| 3.5 | What **failure modes** must we design for: missed flight, hub congestion, no rider, bad address, COD refusal? | Exception flows and **algorithm fallbacks**. |

---

## 4. Service area, “grids,” and where vans do / do not exist

| # | Question | Why it matters |
|---|------------|----------------|
| 4.1 | What does **“grid”** mean to you: administrative zones (pincodes), **hex/rect tiles on a map**, or **operational cells** (each with a cap on orders per time window)? | Same word, very different math. |
| 4.2 | **Who owns** grid definition: operations daily, or mostly **static** with **occasional** rebalance (weekly/monthly)? | Static → precompute; dynamic → more real-time optimization. |
| 4.3 | **How** should grids be formed: **by density** (where demand is), by **road network**, by **SLA** (closer to hub = smaller cells), or **mixed**? | Affects **clustering** (e.g. pincode/POI + constraints). |
| 4.4 | **Minimum and maximum** area or population per grid — any rule of thumb? | Prevents “one huge grid” or “too many micro-grids.” |
| 4.5 | In areas **with no full-time van**, is the plan: **gig bikes**, **third-party courier**, or **“no service”**? Each has different **assignment and cost** models. | Algorithm must know **feasible modes per cell**. |
| 4.6 | **Fixed routes with change**: how often can routes **change** (daily, on surge, on road closure)? **Who** approves a route change: system auto, or ops? | “Dynamic yet efficient” = need **governance** rules, not only math. |

---

## 5. First mile: pickup, riders, and assignment

| # | Question | Why it matters |
|---|------------|----------------|
| 5.1 | **Pickup** is **dedicated to order** (like food) or **consolidation runs** (like courier)? | Affects **batching** and VRP (vehicle routing). |
| 5.2 | **Rider** types: 2W, 3W, 4W van? **Contract types**: full-time, shift-based, on-demand. | Fleet constraints in assignment. |
| 5.3 | For **assigning a rider to a pickup** (or a batch of pickups), what is **most important to optimize**: time to pickup, **cost**, **reliability**, or **balance** of workload? **Weights** or **lexicographic** priority? | This **is** the objective function. |
| 5.4 | **SLA** for pickup: max minutes from “order placed” to “picked at door”? | Hard constraint in optimization. |
| 5.5 | **Reassignment** rules: if rider cancels, traffic spike, or hub says “come later,” what should the system do automatically vs escalate? | Real-time re-optimization. |
| 5.6 | **Capacity**: max parcels per rider / per van per run? | Feasibility in routing. |

---

## 6. Vans, fixed / semi-fixed routes, and dynamics

| # | Question | Why it matters |
|---|------------|----------------|
| 6.1 | Are van routes **fixed sequence of stops** (TSP-like), or **flexible** within a time band? | Algorithm family: fixed template vs VRP with time windows. |
| 6.2 | If routes are “fixed but can change,” **what triggers** a change: new orders, traffic, **hub delay**, or manual override only? | Event-driven re-plan. |
| 6.3 | Is there a **nightly re-plan** (e.g. 11 PM) plus **intra-day patches**, or only intra-day? | How heavy optimization can be. |
| 6.4 | **Max detour** allowed when inserting a new stop into an “almost fixed” route? | Stability vs optimality. |
| 6.5 | **Depot** for vans: same as **first hub** or separate micro-hubs? | Affects return-to-depot in routing. |

---

## 7. Hubs: intake, load, and internal routing

| # | Question | Why it matters |
|---|------------|----------------|
| 7.1 | **Hub layout** (even rough): inbound dock → sort **by flight/destination** → **flight bag** or ULD. Any **bottlenecks** we already know? | **Throughput** and **queueing** in algorithms. |
| 7.2 | **Target dwell time** at hub (parcel inside hub: min/avg/max). | **SLA** for “make the flight” constraint. |
| 7.3 | **How** is load at hub “handled”: **chute and manual sort** at first, or **automation** from day one? | Realism for v1. |
| 7.4 | **Wave-based** or **continuous** flow? (e.g. all parcels for 2 PM flight in one **wave**.) | Batching and **cutoff** rules. |
| 7.5 | If hub is **over capacity**, what is the **escalation**: delay to next flight, **overflow** to third-party, or **reject** booking upstream? | **Back-pressure** in the system. |

---

## 8. To airport: routing, cutoffs, and handoff

| # | Question | Why it matters |
|---|------------|----------------|
| 8.1 | **Mode** to airport: dedicated truck, **van relay**, or **consolidation with other shipments**? | **Leg** in master itinerary. |
| 8.2 | **Time budget** from hub to airport **cargo terminal** (not just the runway): typical + worst case? | **Flights** must be chosen with **taxi time + security/cargo acceptance** buffer. |
| 8.3 | **Who** books cargo: us, EaseMyTrip, or a **GHA**? **Cutoff** for **cargo build-up** (how many hours before STD)? | Direct input to **flight selection** and **order cutoff** in product. |
| 8.4 | If **our truck misses** the cutoff, is there a **next flight** policy, **penalty** to customer, or **same-day** guarantee void? | **Promise** and **exception** policy. |

---

## 9. Air leg (visibility, not full ops in v1)

| # | Question | Why it matters |
|---|------------|----------------|
| 9.1 | For **v1**, is **“best flight on schedule we can buy space on”** enough, or do we need **alliance-level** allocation? | Integration depth with airline. |
| 9.2 | **Liability and tracking**: is **Milestone-based** (scanned on/off flight) enough for v1, or do we need **tag-level** in-air for all tiers? | Cost vs experience. |

---

## 10. After landing: reverse flow (airport to customer)

| # | Question | Why it matters |
|---|------------|----------------|
| 10.1 | Is **dest hub** always the **same** as “sort before last mile” or can we **bypass** hub for some premium lanes? | One vs two internal sorts. |
| 10.2 | **Out-for-delivery** (OFD) waves: how many per day, and **by when** is “EOD” defined? | Last-mile VRP and **promise window**. |
| 10.3 | Is **last mile** the **same** grid and van system as first mile, or **asymmetric** (e.g. hub-only vans inbound, gig 2W outbound)? | **Two** different assignment sub-problems. |
| 10.4 | **POD (proof of delivery)**: photo, OTP, signature? **Reattempts** and cost? | Affects app scope and support load. |

---

## 11. Algorithms: inventory and ownership (per phase)

Use this to **list** every algorithm, **inputs**, **outputs**, **owner** (ops vs eng), and **v1 level** (MVP / rules / full optimization).

| Phase | Sub-problem | Core question for dad |
|--------|--------------|------------------------|
| **A** | **Grid / zone** formation | How often, by what rules, and who approves? |
| **A** | **Serviceability** (book or reject) | Which cells are in/out and under what caps? |
| **B** | **Rider / vehicle assignment** | Objectives, SLAs, replan triggers? |
| **B** | **First-mile / van routes** | Fixed vs VRP, max change per day? |
| **C** | **Hub slotting / load** (even if rules-based at first) | Waves, capacity, back-pressure? |
| **C** | **Hub-to-airport** routing | Buffers, cutoffs, contingencies? |
| **D** | **Flight choice** (with EMT) | Hard constraints and cost tradeoffs? |
| **E** | **Dest hub and OFD** | Waves, last-mile VRP, same as pickup or not? |
| **F** | **End-to-end ETA** | Promise window, confidence, what happens on delay? |

**Ask explicitly:** *For v1, which of these can be **manual + spreadsheet** and which **must** be in software for us to be credible?*

---

## 12. Cost efficiency (greenfield, must be lean)

| # | Question | Why it matters |
|---|------------|----------------|
| 12.1 | What is **Capex** tolerance for v1: hubs, vans, scanners, **vs** **opex**-first (rent, hire, 3P)? | What we can “buy” in automation. |
| 12.2 | **Cost per order** that makes the business **viable** (rough range is fine). | Upper bound for routing (don’t over-route). |
| 12.3 | Willingness to use **3P** for **overflow** (Delhivery last mile, Gati, etc.) when **our** grid is **under-covered**? | Often cheaper than over-building fleet early. |
| 12.4 | **Tech** cost ceiling for **year 1** (cloud, maps, support tools)? | Avoids over-engineering in PoC. |

---

## 13. Stakeholders, systems, and compliance

| # | Question | Why it matters |
|---|------------|----------------|
| 13.1 | Who is **SPOC** for: **operations**, **EaseMyTrip/commercial**, **airport**, **legal**? | PRD will name RACI. |
| 13.2 | **Compliance**: GST invoicing, e-way, **IATA/AWB** (who’s **principal** on air waybill)? | Legal in PRD. |
| 13.3 | **Data** we are allowed to store (customer address, govt ID for high value)? | Privacy section in PRD. |

---

## 14. Success metrics (so PRD is testable)

| # | Question |
|---|----------|
| 14.1 | Top **3** KPIs for v1: e.g. **on-time %**, **cost per delivery**, **NPS**? |
| 14.2 | What **%** of parcels must meet **promised** delivery window? |
| 14.3 | What is **acceptable** **manual** intervention rate (ops hours per 1000 parcels)? |

---

## 15. Gaps the PRD should close (add your notes after the call)

*These are **additional** areas that are easy to miss until launch.*

- **Returns** and **reverse pickup** (even if v2).
- **Customer** **support** and **refund** when algorithm fails.
- **Fraud** (fake addresses, high-value).
- **Observability**: what does ops need on a **single** screen when things go wrong?
- **A/B** or **pilot** city: can we run **old vs new** process in parallel?

---

## 16. Short reference: approaches (for *your* side of the discussion)

Use these only to **ask smarter questions** — you do not need to commit in the room.

| Topic | Common approaches (high level) | Tradeoff (one line) |
|--------|---------------------------------|------------------------|
| **Grids** | K-means / DBSCAN on **pickup+drop latlng**; or **H3/hex** tiles; or **static pincode** lists | Static is cheap; **H3** is standard for “dynamic but stable” map tiles. |
| **Assignment** | **Hungarian** / min-cost **matching** (batch every N min); or **insertion** heuristics into routes | Batching = simpler ops; every-second = heavier infra. |
| **Van + stops** | **VRPTW** (vehicle routing with time windows); “fixed” routes = **template** + small **inserts** with **max detour** | Full VRP = best on paper, needs **data**; templates = controllable. |
| **Hub** | **Waves** + **queue** simulation; no fancy solver v1 if manual sort | **Capacity** and **cutoff** matter more than perfect slotting. |
| **End-to-end ETA** | **Sum of leg SLAs** + buffers; **Monte Carlo** on delays later | Start **conservative** to protect promise. |
| **Cost** | **Capex-light**: rules + 3P overflow; add **solvers** when volume justifies | Align with **12.1–12.3**. |

---

## 17. Suggested “tonight” closing questions

1. *If you had to pick **one** pilot (one origin city, one dest city, one product type), what would it be?*
2. *What is the **one** operational truth I **must** get right in the **PRD** or the whole system fails?*
3. *What can we **promise** the customer in v1 without lying — **time window**, or **“by EOD”**, or **milestone** tracking?*
4. *Between **lowest cost** and **highest customer wow**, which wins if they conflict?*

---

## 18. After this call — your next steps (toward PRD)

1. Fill **TBDs** in this document or a copy.
2. One-page **operational flow** (your diagram) — validate with him once.
3. **PRD outline**: problem, users, stories, out-of-scope, **functional** (ops + algorithms as **features**), **non-functional** (cost, scale), **risks**, **open questions**.
4. Then **design doc** (per algorithm: input/output/constraints) → **PoC** slice.

---

*Document version: 1.0 — for father–son requirements discovery. Adjust sections as you learn more in other meetings.*
