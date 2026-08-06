# OneDay Demo — Full UI Walkthrough (M1 → M6, one dashboard)

Everything runs from **http://localhost:5173**. The top black bar switches between two "worlds":

- **Booking & Ops · M1·M2·M4** — who the user is, what a shipment costs, and booking it (the embedded console).
- **Logistics · M3·M5·M6** — how the city is carved into DA territories, who picks up each parcel, and the vans that carry them hub↔DA.

The whole point: follow **one parcel** from "customer books and pays" (M1/M2/M4) → "a DA is assigned and must make the van" (M5) → "the van drives its loop and meets the DA" (M6).

---

## 0. The mental model (read once)

A one-day parcel must clear this chain **before the flight cutoff**:

```
pickup DA → (cron meeting) → van → HUB → flight → HUB → van → (cron meeting) → delivery DA
```

- **M1 (auth):** identity + roles (customer, DA, station manager, admin).
- **M2 (pricing):** the quote (volumetric weight, city-pair, B2B/B2C, COD, GST).
- **M4 (orders):** the shipment state machine (BOOKED → … → DELIVERED/RTO).
- **M3 (grid):** cuts the city into H3 hexes, groups hexes into **DA territories**.
- **M6 (routing):** lays **van loops** over the hub/airport graph and schedules each DA's **cron meeting** (when a van swings by to swap parcels).
- **M5 (dispatch):** the brain — assigns each pickup to the **least-loaded DA that can still make its cron meeting** (the hard constraint), else **defers** it.

### The one gotcha: two dates
- **Planning** plans for **tomorrow** (`PLAN_DATE`, shown in its header).
- **Execution** and **Dispatch** operate on **today** (`TODAY`).

So Planning is a separate "design tomorrow's plan" story; the live day (Execution + Dispatch) runs on today. The thing that builds *today's* territories is **Execution → "Re-prepare today's plan"**.

---

## 1. World 1 — Booking & Ops (M1 · M2 · M4)

Click **Booking & Ops** in the top bar. This embeds the console (tabs along its own bar):

| Tab | Module | What it means |
|-----|--------|---------------|
| **Auth** | M1 | Log in / create an account. Establishes *who you are* — a JWT with a role. Every later action is gated by that role. |
| **Users** | M1 | Create users (e.g. a DA, a station manager). The actors in the system. |
| **Roles** / **Permissions** | M1 | The 10 actor roles and what each may do (RBAC). |
| **Onboarding** | M1/M4 | Set up a **B2B account** (credit limit, rate card) so it can "Book on Credit". |
| **📦 Book Shipment** | M2 + M4 | The core booking flow (below). |
| **🛒 Cart** | M4 | Multi-parcel + **bulk upload** of destinations (B2B batch booking). |

### Booking a shipment (📦 Book Shipment)
1. **Pick the customer type:** `B2C Customer`, `C2C Customer`, or `B2B User`. This decides pricing + payment path.
2. **Set pickup & drop** — "📍 Set on map" drops pins on the India map; city + pincode are derived from the pins. The backend checks **serviceability** against the real M3 grid (`/api/grid/serviceable-at`) — an unserviceable pin is rejected.
3. **Get the quote (M2):** weight + city-pair → price. Shows base + per-0.5 kg slabs, COD fee (`max ₹50, 1.5%`), 18% GST. (Chennai/MAA is serviceable but unpriced → 422 — a known gap.)
4. **Pay & book (M4):**
   - **Prepaid** → mock Razorpay (`Pay`) → signature verified → shipment **BOOKED**.
   - **Book on Credit** (B2B) → checks the account's credit limit (try **"Demo: Over Credit Limit"** to see the 402 guard), debits outstanding balance, books with no gateway.
   - **COD** → no upfront payment.
5. The shipment now exists in M4 and emits a **`ShipmentCreated`** event on the bus.

> **This is the bridge to logistics:** that `ShipmentCreated` event is what M5 listens for. With a DA shift loaded (World 2), a real booking here flows straight into M5's dispatch queue.

---

## 2. World 2 — Logistics (M3 · M5 · M6)

Click **Logistics**. A toolbar appears: **city selector** + three tabs **Planning · Execution · Dispatch**. Pick **Delhi** (richest grid).

### 2A. Planning tab — M3 territories + M6 routes (plans **tomorrow**)
Left = map of the city's H3 hexes; right = controls; top-right = three map views: **Demand heatmap · DA territories · Van routes**.

1. **Seed demand** (Min/Max minutes, optional Seed for reproducibility) → `POST /api/demo/seed`. Paints each hex with synthetic demand — the surface M3 plans over. Map flips to **Demand heatmap** (darker = busier). *Do this once per day.*
2. **Generate Assignment Plan** — set **DAs for today** (e.g. 10) → **Generate Plan**. Runs M3's **replan** + **approve**: each hex assigned to one DA, workload-balanced. Map flips to **DA territories** (one color per DA). The Plan panel shows `APPROVED · Solver BALANCED_BFS · Coverage 100% · Understaffed 0` and a **DA breakdown** (per-DA minutes vs the target — the ~70%-utilisation cost floor). *The DA count is your input; the solver carves territories among exactly that many DAs.*
3. **Generate Van Routes (M6)** — set **Vans / Capacity / Max cycle** → **Generate Routes**. Runs the M6 VRP solver + approve. Map flips to **Van routes**: colored loops, numbered stops, HUB + AIRPORT markers. The Route panel shows `Vans used / Recommended / Provisioning (UNDER_PROVISIONED if short) / Loops-per-day / Cycle-per-loop`, and **Meeting Vertices: Covered vs Deferred** (red ✗ pins = vertices no van reaches within the cycle cap). Click a **van** to isolate its loop.

> Planning is the "nightly plan" story. To bump coverage, raise **Vans** and re-generate — watch deferred vertices shrink. It does **not** affect today's Dispatch/Execution.

### 2B. Dispatch tab — M5, the assignment brain (operates **today**)
Left = controls + summary + deferred list; right = one card per DA. Needs *today's* territories — run **Execution → Re-prepare** first (see 2C) or it'll show 0 DAs.

**Controls:**
- **Load shift** — pulls today's DA roster from M3 territories, puts each DA on shift, and reads each DA's **cron meeting** (the van rendezvous time + vertex, from M6's cron events). One card per DA appears, showing `cron Xm` (minutes to its meeting).
- **Assign N pickups** (slider) — synthesizes N pickups at random hexes and runs each through the **real M5 engine**: least-loaded DA → cheapest-insertion → **cron feasibility check**. Queued if it fits before the cron; **deferred** otherwise.
- **Assign N deliveries** — the delivery side (inbound parcels → DAs). Tasks show a **D** badge vs **P** for pickups.
- **Work next** — advances each DA's lead task one step: pickup → en-route → van-handoff; delivery → drop-collected → drop-completed (COD on some).
- **Retry deferred** — re-runs assignment for the deferred backlog (some clear as load redistributes).
- **End shift** — defers all still-QUEUED tasks as `SHIFT_ENDED` and sets every DA **OFFLINE**.
- **Reset** — clears M5's state for the day.
- Per-DA **"absent"** button — forces a DA ABSENT (heartbeat lapse); it stops taking pickups, new ones in its tiles defer.
- Hover a **QUEUED** task → **✕** to cancel it.

**What the cards mean:** color bar = territory color; status badge (IDLE / IN_PROGRESS / CRON_LOCKED / ABSENT / OFFLINE); `cron Xm` color-coded by slack (green >60m, amber 30–60m, red <30m). Each queue row: position · **P/D** · shipment · tile · `✓ cron` (fits before the meeting) · `XT` (cross-territory).

**Deferred panel** reasons: `CRON_INFEASIBLE` (would miss the van — the hard constraint working), `NO_DA_AVAILABLE` (no DA covers that hex), `CRON_LOCKED` / `DA_ABSENT` / `SHIFT_ENDED`.

### 2C. Execution tab — M6 run-time + the M5↔M6 handoff (operates **today**)
This plays today forward: vans drive their loops, parcels flow hub↔DA.

**Controls:** plan inputs (DAs / demand / vans / capacity / cycle) → **Re-prepare today's plan** (seeds + builds + approves *today's* M3 territories and M6 routes — **this is what makes Dispatch work**). Then **▼ deliveries / ▲ collects / speed** → **▶ Run the day** / **Stop**.

**What you watch on the map:**
- **Vans** (🚐) animate along their loops; color = on-time/late.
- **👤 DA markers** sit at their cron meeting vertices, with a **📦 badge** = how many pickups M5 queued for that DA.
- When a van reaches a DA's vertex, **both pulse** and a **dashed connector** appears — *the cron meeting / handoff, live*.
- The **RabbitMQ feed** logs it: `LOAD → ARRIVE → SCAN (delivered N, collected M) → RETURN`. Crucially, the **collects come from M5's real queue** — feed lines read `(M5 queue)` and `Sourced N collect(s) from M5's live pickup queue`. So "collected N" at a stop = exactly what M5 dispatched to that DA.

---

## 3. The fully-integrated flow (the money path)

This is the end-to-end you'd demo to show all of M1–M6 connected:

1. **Booking & Ops → Auth** — log in as a customer.
2. **📦 Book Shipment** — pick B2C, set pins in **Delhi**, get the **M2 quote**, **Pay** (Prepaid) → shipment **BOOKED** (M4). → emits `ShipmentCreated`.
3. **Logistics → Execution → Re-prepare today's plan** — builds today's **M3 territories** + **M6 routes** + cron schedule.
4. **Logistics → Dispatch → Load shift** — DAs come on shift with their cron meetings.
   - *(Your real booking from step 2 — and any others — get assigned here by M5's live consumer; or click **Assign N pickups** to add volume.)*
5. Watch M5: queues fill with `✓ cron`; over-promised parcels land in **Deferred** as `CRON_INFEASIBLE`.
6. **Logistics → Execution → Run the day** — vans drive; 👤 DAs light up as vans arrive; the **collects are the parcels M5 dispatched**; feed shows the handoffs.

That's the whole company in one screen: **identity + price + booking (M1/M2/M4) → assignment under the cron constraint (M5) → vans + handoff (M6/M3).**

---

## 4. Glossary — what each term means

- **Hex** — an Uber H3 cell; the unit M3 assigns. ("tile" in M5 ≡ hex.)
- **DA territory** — the set of hexes one delivery associate owns for the day (M3).
- **Cron meeting / cron vertex** — the scheduled time + place a van meets a DA to swap parcels (M6). The **hard constraint**: a pickup is only assignable if the DA can still reach this meeting in time.
- **Cron-safe (`✓ cron`)** — this queued task still lets the DA make its meeting.
- **Cron slack** — minutes until the meeting; small slack → DA gets CRON_LOCKED (frozen).
- **Loop / cycle** — one van round-trip from the hub; cycle = its duration; loops/day = how many it does.
- **Meeting vertex covered/deferred** — whether the plan sends a van to that rendezvous within the cycle cap.
- **Deferral** — M5 couldn't place a parcel now; reason explains why; retried later or escalated to M11.
- **Handoff** — the DA↔van parcel swap at the cron meeting (the pulsing dashed line on the Execution map).

---

## 5. Troubleshooting

- **Dispatch shows "No DAs loaded"** → today's territories don't exist. Run **Execution → Re-prepare today's plan**, then **Load shift**.
- **Everything defers as `NO_DA_AVAILABLE`** → no shift loaded, or stale saturated state — click **Reset**, then Load shift + Assign.
- **DA tooltip says "no van scheduled"** → cron rows were synthesized. For the real DA→van link: **Reset → Re-prepare → Load shift** (lets M6's cron events populate `vanId`).
- **Booking console looks empty / blank frame** → the backend must be up on :8080 (it is, same backend); refresh.
- **"Run the day" errors** → needs RabbitMQ (CloudAMQP via `.env`, or local broker); the run publishes parcel events.
