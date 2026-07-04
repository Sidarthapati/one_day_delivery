# M5 DA-Assignment — End-to-End Demo & Verification Playbook

How to demo M5 (DA assignment) end-to-end and **prove** each step across UI ↔ backend ↔ DB ↔ RabbitMQ,
with its linkages to **M3** (grid), **M4** (orders), and **M6** (routing). Every phase lists: **Do** (UI),
**RabbitMQ** (`rabbitmqadmin`), **DB** (`psql`), and **Expect**.

> Scope: M3–M4–M5–M6 only. The airborne middle (origin-hub → bag → flight → dest-hub = **M7/M9**) is **not
> built**; M5/M6 own the two ground legs (first-mile pickup, last-mile drop) + the grid/dispatch brains.

---

## 0 · One-time setup

**Servers**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
mvn spring-boot:run -pl app          # backend :8080  (serves customer UI + APIs)
( cd demo-ui && npx vite )           # logistics dashboard :5173
```

**rabbitmqadmin** — `~/.rabbitmqadmin.conf` `[oneday]` profile derived from `.env` `CLOUDAMQP_URL`
(host, port 443, ssl=True, username == vhost). Invoke as `rabbitmqadmin -N oneday …`. See CLAUDE.md
"Testing RabbitMQ events from the terminal".

**psql helper** (DEV / Render DB):
```bash
set -a; source .env; set +a
HOST=$(echo "$SPRING_DATASOURCE_URL" | sed -E 's#jdbc:postgresql://([^:/]+):.*#\1#')
DB=$(echo "$SPRING_DATASOURCE_URL"   | sed -E 's#.*:[0-9]+/([^?]+).*#\1#')
PSQL(){ PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" PGSSLMODE=require psql -h "$HOST" -p 5432 -U "$SPRING_DATASOURCE_USERNAME" -d "$DB" -P pager=off "$@"; }
```

**Reference IDs**

| City | cityId | IATA |
|---|---|---|
| Delhi | `f47ac10b-58cc-4372-a567-0e02b2c3d479` | DEL |
| Mumbai | `550e8400-e29b-41d4-a716-446655440000` | BOM |
| Bangalore | `6ba7b810-9dad-11d1-80b4-00c04fd430c8` | BLR |
| Hyderabad | `6ba7b811-…` / Chennai `6ba7b812-…` | HYD / MAA |

**Exchanges / queues (the bus)**

| Exchange | Producer | Consumer queue (binds `#`) |
|---|---|---|
| `oneday.shipments.events` | M4 | `m5.shipments` (M5 — `CREATED→assignPickup`) |
| `oneday.da.events` | M5 | `orders.da` (M4 — OTP/state), `routing.da` (M6 — bindCollect on `PICKUP_COMPLETED`) |
| `oneday.cron.events` | M6 | `m5.cron` (M5 — seat DA on van meeting) |
| `oneday.hub.events` | M7 (demo: Run-the-day) | `routing.hub` (M6 — bindDelivery) |

**Test users** (created for the demo; log in on :8080)

| Lane | Email | Password | Books |
|---|---|---|---|
| C2C | `c2c.demo@oneday.test` | `Customer@123` | `/api/v1/b2c/shipments` |
| B2C | `b2c.demo@oneday.test` | `Customer@123` | `/api/v1/b2c/shipments` |
| B2B | `b2b.demo@oneday.test` | `Business@123` | `/api/v1/b2b/shipments` (account `e235e22f-2d61-4a8e-924c-166d7f735bd5`) |

**⚠️ Date gotcha (do this first every session).** Shifts/crons/tasks are **date-scoped** and seeded only at
startup. If the day has rolled over, the board is empty. Always (re)run **Phase 0 + 1 for today** before demoing.

**Health check (run anytime):**
```bash
rabbitmqadmin -N oneday list queues name consumers messages
```
Healthy = `m5.shipments`, `orders.da`, `routing.da`, `m5.cron`, `routing.hub` each `consumers=1`; all `*.dlq` empty.

---

## A · The happy path (first-mile: book → assign → OTP → pickup → van → hub)

Use **Delhi** (origin) — it has the most coverage. `T=2026-..-..` = today (IST).

### Phase 0 — Preconditions: M3 territories + M6 plan/crons for today  *(M3, M6)*
- **Do:** :5173 **Execution → "Prepare today's plan"** (Delhi). Seeds demand (M3), runs M3 territories (approve), solves M6 routes + per-DA crons.
- **RabbitMQ:** M6 approval publishes on `oneday.cron.events` → M5's `m5.cron` consumes it.
  ```bash
  rabbitmqadmin -N oneday declare queue name=demo.peek.cron durable=false auto_delete=true
  rabbitmqadmin -N oneday declare binding source=oneday.cron.events destination=demo.peek.cron routing_key='#'
  # click Prepare today's plan, then:
  rabbitmqadmin -N oneday get queue=demo.peek.cron count=10 ackmode=reject_requeue_true
  rabbitmqadmin -N oneday delete queue name=demo.peek.cron
  ```
- **DB:**
  ```sql
  -- one APPROVED plan for today
  SELECT status,vans_used,n_loops FROM route_plan
   WHERE city_id='f47ac10b-58cc-4372-a567-0e02b2c3d479' AND valid_for_date=CURRENT_DATE;
  ```
- **Expect:** exactly one `APPROVED` route_plan for today.

### Phase 1 — Load shift  *(M3 → M5)*
- **Do:** :5173 **Dispatch → "1 · Load shift"** (Delhi).
- **DB:**
  ```sql
  SELECT operating_date, count(*) FROM da_cron_assignment
   WHERE city_id='f47ac10b-58cc-4372-a567-0e02b2c3d479' AND operating_date=CURRENT_DATE GROUP BY 1;
  ```
- **Expect:** N `da_cron_assignment` rows for **today** (each DA seated on a meeting vertex; `van_id` set when on the M6 plan). The DA App roster only shows DAs with a **today-cron** (`cronVertexLat != null`).

### Phase 2 — Customer books  *(M4)*
- **Do:** :8080 log in as `c2c.demo` → book a **Delhi-origin** parcel. (COD avoids the payment gateway.)
- **RabbitMQ — prove `CREATED` carries pickup coords (the bug we fixed):**
  ```bash
  rabbitmqadmin -N oneday declare queue name=demo.peek.ship durable=false auto_delete=true
  rabbitmqadmin -N oneday declare binding source=oneday.shipments.events destination=demo.peek.ship routing_key='#'
  # book, then:
  rabbitmqadmin -N oneday get queue=demo.peek.ship count=10 ackmode=reject_requeue_true
  rabbitmqadmin -N oneday delete queue name=demo.peek.ship
  ```
- **DB:**
  ```sql
  SELECT shipment_ref,state,origin_city,dest_city,origin_tile_id,dest_tile_id,payment_mode
    FROM shipments ORDER BY created_at DESC LIMIT 3;
  ```
- **Expect:** a `CREATED` event whose JSON has non-null `origin_lat/origin_lon/origin_tile_id`; shipment row `state=BOOKED` with both tile ids set.

### Phase 3 — M5 auto-assigns the DA  *(M4 → M5; the headline)*
- **Do:** nothing — M5's `ShipmentEventsConsumer` (queue `m5.shipments`) reacts to `CREATED` and runs `assignPickup` in the **origin city** automatically.
- **RabbitMQ:**
  ```bash
  rabbitmqadmin -N oneday list queues name consumers messages | grep -E 'm5.shipments|dlq'
  ```
  `m5.shipments` drains to 0, `consumers=1`, `oneday.shipments.events.dlq=0`.
- **DB — which DA, or why deferred:**
  ```sql
  SELECT city_id,da_id,task_type,status,queue_position FROM dispatch_queue
   WHERE shipment_id=(SELECT id FROM shipments WHERE shipment_ref='1DD-DEL-…');
  SELECT city_id,defer_reason,status FROM deferred_dispatch
   WHERE shipment_id=(SELECT id FROM shipments WHERE shipment_ref='1DD-DEL-…');
  ```
- **Expect:** a `dispatch_queue` PICKUP row in the **origin** city on the **least-loaded** cron-feasible DA. (Mumbai/Bangalore-origin with no shift → `deferred_dispatch … NO_DA_AVAILABLE` instead — see §B1.)

### Phase 4 — Customer reveal + OTP  *(M5 → M4, demo stand-in)*
- **Do:** :8080 **Pickup status** card → paste ref → **"Refresh status"**. The customer never assigns — this reveals M5's decision and mints the OTP. Poll once more: OTP is **stable** (cached).
  ```bash
  curl -s -X POST "http://localhost:8080/api/demo/da/refresh-status?ref=1DD-DEL-…" | python3 -m json.tool
  ```
- **DB:**
  ```sql
  SELECT state FROM shipments WHERE shipment_ref='1DD-DEL-…';            -- PICKUP_ASSIGNED
  SELECT shipment_id, expires_at FROM pickup_otp                          -- hashed OTP row exists
   WHERE shipment_id=(SELECT id FROM shipments WHERE shipment_ref='1DD-DEL-…');
  ```
- **Expect:** `assigned:true`, `da_short`, `otp` (4 digits, stable across refreshes); shipment `PICKUP_ASSIGNED`.

### Phase 5 — DA App shows the pickup  *(M5 + M4 join)*
- **Do:** :5173 **DA App → pick that DA** → its **Pickups** list shows the real **ref + pickup address** + `✓ cron`.
- **Expect:** the task is *verifiable* (OTP box enabled) — i.e. backed by a real M4 shipment, not "synthetic — no booking".

### Phase 6 — OTP handshake → PICKED_UP  *(M4)*
- **Do:** in the DA App, type the OTP → **"Verify pickup"** (or `POST /internal/v1/shipments/{ref}/pickup-otp/verify {"otp":"…"}` → 204).
- **DB:**
  ```sql
  SELECT state FROM shipments WHERE shipment_ref='1DD-DEL-…';            -- PICKED_UP
  SELECT from_state,to_state,created_at FROM shipment_state_history
   WHERE shipment_id=(SELECT id FROM shipments WHERE shipment_ref='1DD-DEL-…') ORDER BY created_at DESC LIMIT 3;
  ```
- **Expect:** `PICKUP_ASSIGNED → PICKED_UP`.

### Phase 7 — DA → van handoff  *(M5 → M6 first-mile)*
- **Do:** :5173 **Dispatch → "Work next"** (advances the lead PICKUP `IN_PROGRESS → recordVanHandoff`), **or** drive it via Execution → Run the day.
- **RabbitMQ — two events on `oneday.da.events`:**
  ```bash
  rabbitmqadmin -N oneday declare queue name=demo.peek.da durable=false auto_delete=true
  rabbitmqadmin -N oneday declare binding source=oneday.da.events destination=demo.peek.da routing_key='#'
  # Work next / Run the day, then:
  rabbitmqadmin -N oneday get queue=demo.peek.da count=20 ackmode=reject_requeue_true
  rabbitmqadmin -N oneday delete queue name=demo.peek.da
  ```
  Look for `PICKUP_COMPLETED` (→ M6 `routing.da` binds the collect) and `VAN_HANDOFF_COMPLETED` (→ M4 `orders.da` → `HANDED_TO_PICKUP_VAN`).
- **DB:**
  ```sql
  SELECT state FROM shipments WHERE shipment_ref='1DD-DEL-…';            -- HANDED_TO_PICKUP_VAN
  ```
- **Expect:** parcel bound to a van loop (`van_manifest_item`), shipment `HANDED_TO_PICKUP_VAN`.

### Phase 8 — Van runs to the hub  *(M6)*
- **Do:** :5173 **Execution → "Run the day"**. The **source readout** tells you if it's driving the **real M5 queue** (green) or **synthetic fallback** (amber). With a real picked-up parcel in the queue it carries *yours*.
- **DB:**
  ```sql
  SELECT status,count(*) FROM van_manifest_item GROUP BY 1;              -- LOADED→IN_PROGRESS→… 
  SELECT van_id,last_event,is_late FROM van_live_status
   WHERE city_id='f47ac10b-58cc-4372-a567-0e02b2c3d479' LIMIT 5;
  ```
- **Expect:** manifest items walk their lifecycle; `van_live_status` animates. Custody scans (`VAN_LOAD/DA_TO_VAN/VAN_UNLOAD`) go through M6's `ScanLedgerPort` — **stub** until M8 lands.

---

## B · M5 edge cases (show the engine is real, not a happy-path script)

### B1 — Deferral (NO_DA_AVAILABLE) + recovery
- **Do:** book a **Mumbai-origin** parcel (Mumbai has no shift). → M5 defers.
- **DB:** `deferred_dispatch … defer_reason='NO_DA_AVAILABLE'`.
- **Recover:** Dispatch → switch to Mumbai → "Load shift" (needs Mumbai territories first) → **"Retry deferred"** → the parcel moves to `dispatch_queue`.

### B2 — Cron-infeasible (the hard constraint)
- **Do:** keep assigning synthetic pickups to one Delhi DA (Dispatch → "Synthetic: N pickups") until one **defers `CRON_INFEASIBLE`** — proves M5 refuses a parcel that can't make its van meeting.
- **DB:** `deferred_dispatch … defer_reason='CRON_INFEASIBLE'`.

### B3 — Cross-territory spill
- **Expect:** when the primary DA is cron-infeasible but an adjacent DA can take it, `dispatch_queue.cross_territory = true` (outcome `CROSS_TERRITORY_ASSIGNED`).

### B4 — DA absent / End shift
- **Do:** Dispatch → "absent" on a DA (heartbeat lapse) → new pickups in its tiles defer; `DA_ABSENT` on `oneday.da.events`. "End shift" → all QUEUED → `SHIFT_ENDED` deferral + DAs OFFLINE.

---

## C · Module linkages (what to point at when asked "how does M5 connect?")

| Linkage | Mechanism | Verify |
|---|---|---|
| **M3 → M5** | door→hex (serviceability), territory→DA roster, cron vertex = van meeting | `GET /api/grid/serviceable-at`; `da_cron_assignment`; Dispatch roster |
| **M4 → M5** | `CREATED` on `shipments.events` → `m5.shipments` → `assignPickup` (origin city) | peek `shipments.events`; `dispatch_queue` |
| **M5 → M4** | OTP + state: `PICKUP_ASSIGNED`/`VAN_HANDOFF_COMPLETED` on `da.events` → `orders.da` | peek `da.events`; `shipments.state` |
| **M5 → M6** | `PICKUP_COMPLETED` on `da.events` → `routing.da` → `bindCollect` | peek `da.events`; `van_manifest_item` |
| **M6 → M5** | route plan published on `cron.events` → `m5.cron` seats DAs | peek `cron.events`; `da_cron_assignment.van_id` |

---

## D · Known gaps & gotchas (be upfront in the demo)

1. **Date rollover empties the board** — shift/crons/tasks are seeded per-day at startup. Re-run Phase 0+1 for *today*.
2. **M5 does not emit `PICKUP_ASSIGNED`** — it assigns but doesn't fire the event, so the M4 transition + OTP is completed by the demo `refresh-status` shim. (`DaEventProducer` has no `emitPickupAssigned` — the real fix is to emit it from `ShipmentEventsConsumer`.)
3. **Last-mile DA assignment is blocked** — `ShipmentEventsConsumer` logs *"reached HANDED_TO_DROP_VAN but delivery assignment is blocked"*. Last-mile (drop) is demo-synthetic (Execution drops / Dispatch "Synthetic deliveries").
4. **Scans hit a stub** — `VAN_LOAD/DA_TO_VAN/VAN_UNLOAD` go to a `ScanLedgerPort` stub; no real M8 append-only ledger yet.
5. **Two synthetic generators** — Dispatch "Synthetic: N" (fake tasks on the queue) and Execution "fallback" drops/pickups (fake bus events). Both are test tools; **real bookings + Run-the-day's M5-queue source are the real path** (Execution readout shows which is active).
6. **Origin-hub onward (M7/M9) not built** — first-mile ends at `VAN_UNLOAD` at the hub dock; M5/M6 do not set `AT_ORIGIN_HUB`.

---

## E · 60-second smoke checklist

1. `rabbitmqadmin -N oneday list queues name consumers messages` → all consumer queues `=1`, DLQs empty.
2. Execution → Prepare today's plan (Delhi) → `route_plan` APPROVED for today.
3. Dispatch → Load shift → `da_cron_assignment` rows for today.
4. :8080 book Delhi-origin (C2C) → `shipments` BOOKED; `CREATED` on the wire with coords.
5. `dispatch_queue` PICKUP row appears (M5 auto-assigned) — no clicks.
6. :8080 Refresh status → DA + stable OTP; shipment `PICKUP_ASSIGNED`.
7. DA App → Verify OTP → `PICKED_UP`.
8. Run the day → green "M5 real queue" source → van carries it to the hub.
