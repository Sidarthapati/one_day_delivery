# M8 — DB Queries to Show the Business Owner

The demo UI shows the parcel moving and the live event feed. **The DB is where the *complete, permanent record* lives** — this is what you open in TablePlus (DEVELOPMENT connection → `singapore1dd`) to prove the chain of custody and its immutability.

---

# ⭐ ONE-PARCEL DEMO (auto-picks the latest booking — no ref to copy)

You book **one** parcel via the UI, then run these in order. Each query auto-selects the most recent booking, so there's nothing to paste. **This is the set to use for the live business-owner demo.**

**What the DB adds beyond the 2 events on the RabbitMQ feed:** the actual **barcode string**, the **complete 17-step lifecycle** (the feed/UI only shows 5 phases), the **immutability guarantee**, and the **barcode numbering**.

### L1 — The parcel and its barcode
> *"This is the box. Its permanent barcode is `1DD-BOM-…`."*
```sql
SELECT shipment_ref, parcel_id AS barcode, origin_city, dest_city, state,
       payment_mode, total_price_paise/100.0 AS price_inr, created_at
FROM shipments
ORDER BY created_at DESC
LIMIT 1;
```

### L2 — The two scan events, now permanent (this is what you saw on the feed)
> *"The two events on the live feed weren't just messages — they're written here forever, timestamped."*
```sql
SELECT scan_type, location_type AS scanned_at_node, parcel_id AS barcode,
       actor_id AS scanned_by, scanned_at
FROM scan_ledger
WHERE shipment_id = (SELECT id FROM shipments ORDER BY created_at DESC LIMIT 1)
ORDER BY scanned_at;
```

### L3 — ⭐ The COMPLETE journey (every state, booked → delivered)
> *"And here's the whole journey — all 17 steps the parcel passed through, end to end."*
```sql
SELECT row_number() OVER (ORDER BY occurred_at) AS step,
       from_state, to_state, trigger_source, occurred_at
FROM shipment_state_history
WHERE shipment_id = (SELECT id FROM shipments ORDER BY created_at DESC LIMIT 1)
ORDER BY occurred_at;
```

### L4 — ⭐ Tamper-proof (run it — the DB refuses)
> *"Try to change history — the database itself says no."*
```sql
UPDATE scan_ledger SET scan_type = 'TAMPERED'
WHERE shipment_id = (SELECT id FROM shipments ORDER BY created_at DESC LIMIT 1);
--  ERROR:  scan_ledger is append-only: UPDATE is not permitted
```

### L5 — Barcode numbering (per destination hub, per day)
> *"Every hub issues its own daily sequence; a number is used once and never reused."*
```sql
SELECT hub_iata, day, next_seq AS next_number
FROM parcel_id_counter
WHERE day = current_date
ORDER BY hub_iata;
```

### L6 — Retry-safe (a double-beep never duplicates)
```sql
-- Zero rows = every scan is unique on its device key; retries collapse to one row.
SELECT client_scan_id, count(*) AS copies
FROM scan_ledger
WHERE client_scan_id IS NOT NULL
GROUP BY client_scan_id
HAVING count(*) > 1;
```

> **Reality check for this demo:** a parcel booked through the **UI one-button run** has **2 scans** in the ledger (`LABEL_GENERATED` + `DELIVERED`) — exactly the two events on your feed — plus the **full 17-step state history**. The intermediate **van-custody and hub scans are not on this parcel** (those legs are fast-forwarded / bound to the manifest, not the booking). To make the *single* parcel show the full 11-scan chain of custody in the ledger AND the feed, apply the "full-trail" wiring fix (ask the engineer). Until then, lead with **L3 (complete journey)** as your end-to-end proof, not the scan count.

---

## Reference: keyed-by-ref queries (for a specific/older parcel)

*(Use these if you want a parcel other than the latest — e.g. a runbook-driven one that already has the full 11-scan trail. Run Q0 to find one, paste its ref into `PUT-REF-HERE`.)*

**How to use:** every query is keyed by a human tracking ref. Run **Q0 first** to pick a parcel with the richest trail, then paste its ref into `Q1`–`Q7` (replace `PUT-REF-HERE`).

> **Which parcel to pick:** a parcel driven by the `m8-demo.md` runbook has the **full 11-scan trail** (label → van → hub in/out → airport → shuttle → dest hub → van → delivered). A parcel from the UI one-button run currently shows **label + delivered + van custody** only (the hub/airport legs are fast-forwarded). Q0 ranks by trail richness so you always pick the best one.

---

## Q0 — Pick the best parcel to demo (run this first)
```sql
SELECT sh.shipment_ref,
       count(*)                                              AS scans,
       count(*) FILTER (WHERE s.scan_type = 'LABEL_GENERATED') AS label,
       count(*) FILTER (WHERE s.location_type = 'VAN')         AS van_custody,
       count(*) FILTER (WHERE s.scan_type LIKE 'HUB%'
                          OR s.scan_type IN ('GHA_ACCEPTANCE','DEST_SHUTTLE_IN')) AS hub_air,
       count(*) FILTER (WHERE s.scan_type = 'DELIVERED')       AS delivered,
       max(s.scanned_at)                                       AS last_scan
FROM scan_ledger s
JOIN shipments sh ON sh.id = s.shipment_id
WHERE s.scanned_at::date = current_date
GROUP BY sh.shipment_ref
ORDER BY scans DESC, last_scan DESC
LIMIT 15;
```
Pick a ref with the highest `scans` (ideally label + van_custody + hub_air + delivered all non-zero).

---

## Q1 — ⭐ The full chain of custody (the money shot)
> *"One box, its whole life — who scanned it, where, when — in order."*
```sql
SELECT row_number() OVER (ORDER BY scanned_at) AS step,
       scan_type,
       location_type       AS scanned_at_node,
       parcel_id           AS barcode,
       location_id         AS node_id,
       actor_id            AS scanned_by,
       counterparty_id     AS handed_to,
       scanned_at
FROM scan_ledger
WHERE shipment_id = (SELECT id FROM shipments WHERE shipment_ref = 'PUT-REF-HERE')
ORDER BY scanned_at;
```

## Q2 — Van custody only (who physically held the box between hubs)
> *"Every van-to-driver hand-off, recorded — which van, which driver, which associate."*
```sql
SELECT scan_type          AS handoff,       -- VAN_LOAD / DA_TO_VAN / VAN_TO_DA / VAN_UNLOAD
       location_id        AS van_id,
       actor_id           AS driver_id,
       counterparty_id    AS delivery_associate_id,
       scanned_at
FROM scan_ledger
WHERE shipment_id = (SELECT id FROM shipments WHERE shipment_ref = 'PUT-REF-HERE')
  AND location_type = 'VAN'
ORDER BY scanned_at;
```

## Q3 — ⭐ Tamper-proof (run BOTH — each must ERROR)
> *"Not even we can change it. The database itself refuses."*
```sql
UPDATE scan_ledger SET scan_type = 'TAMPERED'
WHERE shipment_id = (SELECT id FROM shipments WHERE shipment_ref = 'PUT-REF-HERE');
--  ERROR:  scan_ledger is append-only: UPDATE is not permitted

DELETE FROM scan_ledger
WHERE shipment_id = (SELECT id FROM shipments WHERE shipment_ref = 'PUT-REF-HERE');
--  ERROR:  scan_ledger is append-only: DELETE is not permitted
```

## Q4 — Barcode identity (the shipment and its permanent barcode)
```sql
SELECT shipment_ref, parcel_id AS barcode, origin_city, dest_city,
       state, payment_mode, total_price_paise/100.0 AS price_inr
FROM shipments
WHERE shipment_ref = 'PUT-REF-HERE';
```

## Q5 — Barcode numbering (per-hub, per-day sequence — never reused)
> *"Each destination hub issues its own daily sequence; a number is burned once, never again."*
```sql
SELECT hub_iata, day, next_seq AS next_number_to_issue
FROM parcel_id_counter
ORDER BY day DESC, hub_iata;
```

## Q6 — Retry-safe (a double-beep never duplicates a row)
> *"If a scanner fires twice on a bad signal, we still store exactly one line."*
```sql
-- Zero rows returned = no duplicates anywhere; every scan is unique on its device key.
SELECT client_scan_id, count(*) AS copies
FROM scan_ledger
WHERE client_scan_id IS NOT NULL
GROUP BY client_scan_id
HAVING count(*) > 1;
```

## Q7 — The full status journey (complete, even where scans are fast-forwarded)
> *"The customer's status timeline — every state the parcel passed through."*
```sql
SELECT from_state, to_state, trigger_source, occurred_at
FROM shipment_state_history
WHERE shipment_id = (SELECT id FROM shipments WHERE shipment_ref = 'PUT-REF-HERE')
ORDER BY occurred_at;
```
`trigger_source = KAFKA_EVENT` means that state change was driven by an event on the bus (a scan or a downstream reaction); `API` is the original booking.

---

## Bonus — live scan activity across all parcels (last 30 min)
Good to leave running on a second screen while the demo runs — new rows appear as parcels are scanned.
```sql
SELECT sh.shipment_ref, s.scan_type, s.location_type, s.parcel_id, s.scanned_at
FROM scan_ledger s
JOIN shipments sh ON sh.id = s.shipment_id
WHERE s.scanned_at > now() - interval '30 minutes'
ORDER BY s.scanned_at DESC
LIMIT 40;
```

---

### Talking order for the owner
1. **Q1** — the whole life of one parcel, in one screen.
2. **Q2** — zoom into the physical hand-offs (custody).
3. **Q3** — try to edit it → the DB refuses. *(This is the moment that lands.)*
4. **Q5 / Q6** — the barcode is unique-per-day and retry-safe.
5. **Q7** — tie it back to the customer's status timeline.
