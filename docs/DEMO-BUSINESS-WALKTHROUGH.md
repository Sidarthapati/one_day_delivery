# One-Day Delivery — Business-Owner Demo (Live RabbitMQ Feed + Full Flow)

A single, repeatable script to show a non-technical stakeholder the **whole parcel journey — first mile to last mile — with the real event bus lighting up live** on screen.

Two windows, one button, real data.

---

## 0. What they'll see

| Window | URL | Role in the demo |
|---|---|---|
| **Customer app** | `http://localhost:8080/` | The parcel-sender's view — book a parcel, watch its status, get handover/OTP codes. |
| **Ops console** | `http://localhost:5173/` | Our operation. Tabs: **Execution** (map + "Run the day" + **live RabbitMQ feed**), **Dispatch** (M5 control tower), **DA Phone**. |

The star of the show is the **live RabbitMQ feed** on the Execution tab: every message the system publishes and consumes streams in **as it happens**, colour-coded by event. It is a real tap on the live broker (`AmqpTap`), not a mock — publishes and their matching consumes appear in pairs, proving the modules are actually talking over the bus.

---

## 1. Preconditions (once, before they arrive)

| # | Check | Command / action | Pass |
|---|---|---|---|
| P1 | JDK 21 | `java -version` | `21.x` (NOT 25) |
| P2 | `.env` sourced | `set -a; source .env; set +a` | `SPRING_DATASOURCE_URL`, `CLOUDAMQP_URL`, `ROUTING_OSRM_BASEURL` all set |
| P3 | Own broker | `CLOUDAMQP_URL` → `…@puffin.rmq2.cloudamqp.com/rqdfauba` | isolated `1dd-agniva` (no teammate contention) |
| P4 | DB | psql one-liner → `SELECT current_database();` | `singapore1dd` |
| P5 | OSRM | `curl -s "$ROUTING_OSRM_BASEURL/table/v1/driving/77.2,28.6;77.3,28.7" \| head -c 40` | JSON `{"code":"Ok"…}` (else the van plan won't load) |

**Boot both servers** (from the packaged jar — immune to the "torn build" 401):
```bash
./run-demo.sh --build     # first boot of the day (or after any code change)
./run-demo.sh             # subsequent boots
```
`:8080` (customer) + `:5173` (ops) both come up. Startup ~20s; the 5 city grids + DA shifts self-seed for **today**.

### Broker health — the one thing to verify before demoing the feed
Every consumer must be **bound** and every dead-letter queue **empty**, or the live feed looks broken (publishes with no consumes):
```bash
rabbitmqadmin -N oneday list queues name consumers messages | grep -vE 'amq\.'
```
- **Expect:** all 13 consumer queues (`orders.*`, `m5.*`, `routing.*`, `grid.*`, `hub.flight`) show `consumers = 1`.
- **Expect:** every `*.dlq` shows `messages = 0`.

> **If a `*.events.dlq` is non-empty**, an event failed a contract — fix before demoing. Clear old cruft with `rabbitmqadmin -N oneday purge queue name=<the.dlq>`.
>
> **`hub.shipments` will show a growing message count with `consumers = 0` — this is by design.** M7's hub consumer is `autoStartup=false` (in this demo M7 is driven via REST, not this queue), so it's M7's dormant inbox. It never appears in the live feed (no consumer = no CONSUME events) and is harmless. Only visible if you open the CloudAMQP console — the business owner won't.

---

## 2. The walkthrough (≈4 minutes, one button)

Put the **Ops console → Execution tab** on the main screen so the **live feed** panel is visible. Keep the **Customer app** on a second screen/tab.

### Step 1 — "A customer books a parcel"
On the Customer app (`:8080`), book a Delhi → Mumbai parcel (or, for the scripted one-button flow, use the Execution tab's full-day controls).

**On the live feed, point out — this is the whole system reacting in real time:**

| Live feed line | Plain English |
|---|---|
| `PUBLISH  oneday.shipments.events  ShipmentCreatedEvent` | The order was placed. |
| `CONSUME  m5.shipments  ShipmentCreatedEvent` | **Dispatch (M5) heard it** and is assigning a delivery associate. |
| `PUBLISH  oneday.da.events  DaLifecycleEvent PICKUP_ASSIGNED` | M5 assigned a DA. |
| `CONSUME  orders.da` **+** `CONSUME  routing.da` | **Orders (M4) and Routing (M6) both heard it** — the DA's pickup is on the books and on a van route. |
| `PUBLISH  oneday.shipments.events  ShipmentStateChangedEvent BOOKED→PICKUP_ASSIGNED` | The parcel's status advanced. |

> Talking point: *"No module calls another directly. They announce facts on the bus and everyone who cares reacts. That's how we scale and stay auditable."*

### Step 2 — "Run the day"
Press **Run the day** (full-day intercity run). The map animates vans; the feed keeps streaming. The parcel flows through:

**First mile** → DA collects (OTP) → van collects at the meeting point → hub.
**Origin hub (M7, real)** → sorted into a flight bag → bag sealed → dispatched to airport.
**Flight** → simulated air leg → lands at destination.
**Destination hub (M7, real)** → sorted for delivery → emits `ParcelSortedForDelivery`, which **M6 binds to a delivery van** (watch `oneday.hub.events` publish → `routing.hub` consume).
**Last mile** → drop van → delivery DA → recipient OTP → **delivered**.

### Step 3 — "Where is my parcel?"
Flip to the Customer app. The parcel's status has tracked every step (Booked → Picked up → In transit → Out for delivery → **Delivered**), driven entirely by the events they just watched fire.

### Step 4 — "And it's all recorded, immutably" (the M8 barcode/scan ledger)
Every physical touch of the box wrote one **append-only** scan row. Show the trail for one parcel:
```sql
SELECT scan_type, location_type, parcel_id, scanned_at
FROM scan_ledger WHERE shipment_id = '<id>' ORDER BY scanned_at;
```
`LABEL_GENERATED → van custody (VAN_LOAD/VAN_TO_DA/DA_TO_VAN/VAN_UNLOAD) → DELIVERED` — a complete, un-editable chain of custody. The database itself rejects any edit (`scan_ledger is append-only`).

---

## 3. The event choreography (verified live)

The bus is real; this is what actually fires, in order, for one parcel:

| # | Producer → event → exchange | Consumer(s) | Effect |
|---|---|---|---|
| 1 | M4 → `ShipmentCreatedEvent` → `oneday.shipments.events` | M5 (`m5.shipments`) | M5 assigns pickup DA |
| 2 | M5 → `DaLifecycleEvent PICKUP_ASSIGNED` → `oneday.da.events` | M4 (`orders.da`), M6 (`routing.da`) | pickup on the books + on a van route; OTP minted |
| 3 | M4 → `ShipmentStateChangedEvent` → `oneday.shipments.events` | M5 (`m5.shipments`) | lifecycle tracked |
| 4 | M8 → `ScanEvent` (LABEL/journey) → `oneday.scan.events` | M4 (`orders.scan`) | stamps `parcel_id` / advances state |
| 5 | M7 → `ParcelSortedForDelivery` → `oneday.hub.events` | M6 (`routing.hub`), M4 (`orders.hub`) | M6 binds the parcel to a delivery van |
| — | van custody scans (M6 → M8) | *(synchronous, in-process)* | immutable custody rows, **no bus event** by design |

All consumes visible in the live feed; DLQ stays at 0 throughout a clean run.

---

## 4. If something looks wrong

| Symptom | Cause | Fix |
|---|---|---|
| Feed shows publishes but **no matching consume** | a consumer queue isn't bound | `rabbitmqadmin -N oneday list queues name consumers` — any app queue at `consumers=0` (except `hub.shipments`/`hub.flight`) is the culprit; restart the app |
| `"No plan loaded for today"` | OSRM down or no approved M6 plan | check P5; the Execution tab's **Prepare** builds today's territories + van plan |
| A `*.events.dlq` climbing | an event contract mismatch (wrong `__TypeId__`) | stop, inspect the DLQ message, fix the producer/consumer type |
| Customer booking → 401 | needs a real customer JWT | book through the UI (it logs in), not raw curl |

> **Never** `mvn clean install` while the app is live (torn classes → phantom 401s). `run-demo.sh` runs the jar to avoid this; rebuild only after stopping.
