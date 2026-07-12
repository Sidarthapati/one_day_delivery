# M8 (Barcode & Scan Ledger) — Business-Owner Demo Plan

**Audience:** the business owner, watching our two screens.
**Goal:** show that **every parcel is barcoded once, then every physical touch is recorded in an un-editable ledger** — and that this happens automatically, live, as the parcel moves first-mile → last-mile.

**Duration:** ~5 minutes. One button does the whole run.

---

## What M8 is (say this first, in plain words)

> *"When a driver picks up a box, we print a barcode on it — that ID is the parcel's identity for life. From then on, every time anyone scans that box — driver, van, hub, delivery — we write one permanent line to a ledger. The ledger can never be edited or deleted, not even by us. That's how we always know exactly where a parcel is and prove who held it and when. If a parcel is ever disputed or lost, this record settles it."*

Nothing about M8 is flashy. Its value is that the record is **complete, automatic, and tamper-proof.**

---

## The two screens

| Screen | URL | What the owner watches for M8 |
|---|---|---|
| **Ops console** | `http://localhost:5173/` → **Execution** tab | The **live RabbitMQ feed** (right-hand panel). Scan events stream in real time as the barcode is created and the parcel is delivered. |
| **Customer app** | `http://localhost:8080/` | The parcel's **live status** — which is *derived from those scans*. Proof the ledger drives what the customer sees. |
| **The ledger itself** | one SQL query (below) | The **money shot**: the full, ordered, un-editable custody trail for one parcel. |

---

## Pre-flight (presenter, before they arrive)

1. Boot both UIs: `./run-demo.sh --build` → `:8080` and `:5173` up.
2. Broker healthy (so the live feed is clean):
   ```bash
   rabbitmqadmin -N oneday list queues name consumers messages | grep -vE 'amq\.'
   ```
   Every app queue `consumers=1`; every `*.dlq` `messages=0`. *(This was previously broken by a bean-name collision — now fixed; all consumers bind and DLQ stays at 0.)*
3. Have a terminal ready with the **ledger query** (Step 4) so you can run it on cue.
4. Open the Ops console **Execution** tab so the live feed is visible.

---

## The demo (one button, four beats)

### Beat 1 — "Pick up the parcel, print the barcode"
Book a Delhi → Mumbai parcel (Customer app, or the Execution tab's full-day controls), then press **Run the day**. The first thing that happens at pickup:

**On the live feed, point out:**
```
PUBLISH  oneday.scan.events   ScanEvent  LABEL_GENERATED
CONSUME  orders.scan          ScanEvent  LABEL_GENERATED
```
> *"The driver just scanned the box. M8 minted its barcode — `1DD-BOM-260712-000014` — and announced it. The Orders module heard it and stamped that ID onto the shipment. The parcel now has a permanent identity."*

### Beat 2 — "Watch it change hands, recorded every time"
As the run animates the vans, each hand-off writes a **custody row** — which van, which driver, which delivery associate, at what time. These are deliberately *quiet* (recorded, not broadcast — the van isn't a public event), so they show up in the ledger, not the feed. Mention it:
> *"Every van-to-driver hand-off is being written to the ledger right now — silently, because it's an internal custody record, not something we broadcast. We'll see them all in a moment."*

### Beat 3 — "Delivered — proof at the door"
At the last mile:
```
PUBLISH  oneday.scan.events   ScanEvent  DELIVERED
CONSUME  orders.scan          ScanEvent  DELIVERED
```
Flip to the **Customer app** — the parcel now reads **Delivered**.
> *"The delivery scan is logged as proof the right box reached the door. Notice the customer's status tracked every step — because the status IS the scans. We don't type status updates; the parcel reports itself."*

### Beat 4 — "And it's all permanent" (the money shot)
Run the ledger query for that parcel:
```sql
SELECT scan_type, location_type, parcel_id, scanned_at
FROM scan_ledger
WHERE shipment_id = '<the shipment id>'
ORDER BY scanned_at;
```
Real output from a live run:
```
    scan_type    | location_type |       parcel_id       |          scanned_at
-----------------+---------------+-----------------------+------------------------------
 LABEL_GENERATED | DA            | 1DD-BOM-260712-000014 | 2026-07-12 15:07:00 ← barcode printed at pickup
 DA_TO_VAN       | VAN           |                       | 2026-07-12 15:07:14 ← handed to the first-mile van
 VAN_UNLOAD      | VAN           |                       | 2026-07-12 15:07:21 ← unloaded at the hub
 VAN_LOAD        | VAN           |                       | 2026-07-12 15:07:39 ← loaded on the delivery van
 VAN_TO_DA       | VAN           |                       | 2026-07-12 15:07:45 ← handed to the delivery associate
 DELIVERED       | DA            | 1DD-BOM-260712-000014 | 2026-07-12 15:07:48 ← delivered at the door
```
> *"One box, its whole life — who touched it, when, where — in order. Now watch what happens if someone tries to change history:"*
```sql
UPDATE scan_ledger SET scan_type = 'HACKED' WHERE shipment_id = '<id>';
```
```
ERROR:  scan_ledger is append-only: UPDATE is not permitted
```
> *"The database itself refuses. Not our app code — the database. Nobody can rewrite or erase a scan. That's the guarantee."*

---

## The three things to leave them with

1. **Automatic** — the driver scans; the barcode, the ledger, and the customer's status all follow with zero manual data entry.
2. **Complete** — pickup, every hand-off, delivery — one immutable line each, queryable as a single trail per parcel.
3. **Tamper-proof** — the ledger physically rejects edits and deletes. The audit trail can be trusted in a dispute.

*(Bonus if asked "what if a scanner double-beeps?" — a repeated scan collapses to one row; retries never create duplicates.)*

---

## If something looks off (presenter cheat-sheet)

| Symptom | Fix |
|---|---|
| Live feed shows a publish but no matching consume | a consumer isn't bound — `rabbitmqadmin -N oneday list queues name consumers`; restart the app |
| No `LABEL_GENERATED` on the feed at pickup | the barcode service didn't fire — check the app is up and the parcel actually reached pickup (`autoVerify`) |
| `hub.shipments` shows a growing count on the broker console | **by design** — M7's hub consumer is dormant in this demo (driven via REST); it never appears in the live feed. Ignore. |
| A `*.events.dlq` is non-zero | an event contract broke — do not demo until it's 0 (`purge` old cruft, restart) |

> Don't run `mvn clean install` while the app is live. Use `run-demo.sh` (runs the packaged jar); rebuild only after stopping.
