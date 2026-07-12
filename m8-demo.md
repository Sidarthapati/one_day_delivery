# M8 (barcode / scan-ledger) — Boot & Drive Runbook

Branch `M8`. Boot the assembled app, book one parcel, hand-drive it **first-mile → last-mile**
through the M8 scan doors, and verify the append-only `scan_ledger` end-to-end — including the
live `ScanEvent → M4` state seam and the PR4 van-custody adapter.

**Environment facts (verified live, not assumed):**
- Active profile is **`dev`** (a `!prod` profile) → `DemoAuthFilter`, `DemoSecurityConfig`,
  `MockPaymentController`, `GridSeeder` are live. The M8 scan doors and `/api/**` need **no JWT** (a
  synthetic ADMIN principal is injected); **booking** still needs a real customer JWT
  (`requireCustomerRole` has no ADMIN bypass); **`/routing/**` is NOT in the demo permit-all matcher**
  (only `/api/**` + `/internal/**`) so routing endpoints need a Bearer token.
- **All JSON bodies are snake_case** (`spring.jackson.property-naming-strategy: SNAKE_CASE`) — send
  `shipment_id`, `scan_type`, … camelCase silently binds to null → `422`.
- **Every POST needs an `Idempotency-Key` header** (a global `IdempotencyFilter`), not just booking.
- `.env` points at Render **Singapore** `singapore1dd`. Source `.env` in **both** the app shell and
  the psql shell so they hit the same DB.
- **Broker isolation:** the `ScanEvent → M4` seam runs over CloudAMQP. If you share one instance with
  a teammate, their consumer can grab and reject your `orders.scan` messages (→ DLQ, state won't
  advance). Use **your own** CloudAMQP instance: set `CLOUDAMQP_URL` in `.env`, and separately point
  `~/.rabbitmqadmin.conf` at the same host (that file does **not** read `CLOUDAMQP_URL`).

---

## 1. Boot the app

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21     # JDK 21 mandatory (enforcer rejects JDK 25)
set -a; source .env; set +a                        # SPRING_DATASOURCE_* + CLOUDAMQP_URL
mvn spring-boot:run -pl app                         # → http://localhost:8080/
```
Wait for `Started OneDayDeliveryApplication`; confirm the broker line `Created new connection: …`.

## 2. Helpers (psql + a POST wrapper)

Second shell, repo root:
```bash
set -a; source .env; set +a
BASE=http://localhost:8080
HOST=$(echo "$SPRING_DATASOURCE_URL" | sed -E 's#jdbc:postgresql://([^:/]+):.*#\1#')
DB=$(echo   "$SPRING_DATASOURCE_URL" | sed -E 's#.*:[0-9]+/([^?]+).*#\1#')
psql_dev() { PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" PGSSLMODE=require \
  psql -h "$HOST" -p 5432 -U "$SPRING_DATASOURCE_USERNAME" -d "$DB" "$@"; }
# POST wrapper: always sends Content-Type + a fresh Idempotency-Key. Pass extra -H/-d after it.
jpost() { curl -s -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -X POST "$@"; }
psql_dev -c "SELECT current_database();"   # expect: singapore1dd
```

## 3. Get a customer JWT

`POST /auth/register` assigns `C2C_CUSTOMER` and returns the token in `.token` (no separate login).
```bash
TOKEN=$(jpost "$BASE/auth/register" -d "{
  \"email\":\"m8demo+$(date +%s)@example.com\",\"password\":\"password123\",
  \"name\":\"M8 Demo\",\"phone\":\"+919000000001\"}" | jq -r '.token')
echo "TOKEN=${TOKEN:0:24}..."
```

## 4. Book one COD parcel (Delhi → Mumbai = INTERCITY)

COD has no payment step. `drop_type` is `DA_DELIVERY`; each address needs `line1`+`city`+`pincode`+
`state`+lat/lon inside a seeded hex. **`BookingResponse` returns `shipment_ref`, not the UUID** — the
scan doors key on the shipment UUID, so resolve it from the DB.
```bash
REF=$(jpost "$BASE/api/v1/b2c/shipments" -H "Authorization: Bearer $TOKEN" -d '{
  "sender_name":"A","sender_phone":"+919000000001",
  "origin_address":{"line1":"1 MG Rd","city":"delhi","pincode":"110001","state":"Delhi","latitude":28.61,"longitude":77.20},
  "origin_city":"delhi","origin_pincode":"110001",
  "receiver_name":"B","receiver_phone":"+919000000002",
  "dest_address":{"line1":"2 Marine Dr","city":"mumbai","pincode":"400001","state":"Maharashtra","latitude":19.07,"longitude":72.87},
  "dest_city":"mumbai","dest_pincode":"400001",
  "weight_grams":1000,"length_cm":10,"width_cm":10,"height_cm":10,
  "pickup_type":"DA_PICKUP","drop_type":"DA_DELIVERY","payment_mode":"COD"
}' | jq -r '.shipment_ref')
SID=$(psql_dev -tAc "SELECT id FROM shipments WHERE shipment_ref='$REF';")
echo "REF=$REF  SID=$SID"
psql_dev -c "SELECT state, parcel_id, delivery_type FROM shipments WHERE id='$SID';"  # BOOKED, null, INTERCITY
```

## 5. First mile — mint the label (`LABEL_GENERATED`)

```bash
jpost "$BASE/api/v1/scan/label" -d "{
  \"shipment_id\":\"$SID\",\"dest_city\":\"BOM\",\"actor_id\":\"$(uuidgen)\",\"client_scan_id\":\"$(uuidgen)\"}" | jq .
sleep 3
psql_dev -c "SELECT scan_type, parcel_id, location_type FROM scan_ledger WHERE shipment_id='$SID';"
psql_dev -c "SELECT state, parcel_id FROM shipments WHERE id='$SID';"   # parcel_id now stamped (M4 seam)
```
Expect `parcel_id` like `1DD-BOM-260712-000001` (per-hub-per-day counter). The stamp arriving proves
`ScanEvent → oneday.scan.events → orders.scan → M4` is live.

## 6. Lifecycle scans + live M4 state walk

M8 owns only 5 transitions; the gaps (pickup OTP, van handoff, hub/flight events, drop OTP) belong to
modules not wired on this branch. So **pre-position** the shipment at each prerequisite state via a
direct `UPDATE shipments SET state=...` (allowed — the append-only trigger is only on `scan_ledger`),
fire the scan, and assert the live consumer advanced it.

```bash
walk () {  # $1=prereq $2=scanType $3=target $4=locType $5=minsOffset
  psql_dev -qc "UPDATE shipments SET state='$1'::shipment_state WHERE id='$SID';"
  jpost "$BASE/api/v1/scan" -o /dev/null -d "{
    \"shipment_id\":\"$SID\",\"scan_type\":\"$2\",\"location_type\":\"$4\",
    \"location_id\":\"$(uuidgen)\",\"actor_id\":\"$(uuidgen)\",\"client_scan_id\":\"$(uuidgen)\",
    \"scanned_at\":\"$(date -u -v+${5}M +%Y-%m-%dT%H:%M:%SZ)\"}"
  sleep 5
  printf "  %-16s -> %s (want %s)\n" "$2" "$(psql_dev -tAc "SELECT state FROM shipments WHERE id='$SID';")" "$3"
}
walk HANDED_TO_PICKUP_VAN  HUB_ORIGIN_IN    AT_ORIGIN_HUB         HUB     5
walk IN_TAKEOFF_BAG        HUB_ORIGIN_OUT   DISPATCHED_TO_AIRPORT HUB     10
walk DISPATCHED_TO_AIRPORT GHA_ACCEPTANCE   AT_AIRPORT            AIRPORT 15
walk LANDED                DEST_SHUTTLE_IN  DISPATCHED_TO_HUB     SHUTTLE 20
walk DISPATCHED_TO_HUB     HUB_DEST_IN      AT_DEST_HUB           HUB     25
```

**Last mile — `DELIVERED` (ledger-only, Option A):** records a custody row but drives **no** transition
(DROPPED stays OTP-owned).
```bash
psql_dev -qc "UPDATE shipments SET state='AT_DEST_HUB'::shipment_state WHERE id='$SID';"
jpost "$BASE/api/v1/scan" -o /dev/null -d "{\"shipment_id\":\"$SID\",\"scan_type\":\"DELIVERED\",\"location_type\":\"DA\",\"client_scan_id\":\"$(uuidgen)\",\"scanned_at\":\"$(date -u -v+30M +%Y-%m-%dT%H:%M:%SZ)\"}"
sleep 4; psql_dev -tAc "SELECT state FROM shipments WHERE id='$SID';"   # stays AT_DEST_HUB
```

## 7. Van custody scans (PR4 — the `@Primary` adapter beats NoOp)

Van scans enter via M6's sync port, not the HTTP scan door; under D-001 the routing `parcel_id` **is**
the shipment UUID. The ledger write is unconditional (before any manifest lookup), so no route
plan/manifest is needed. **`/routing/vans/**` needs the Bearer token**; the telemetry door
`/api/v1/van/**` is permit-all.
```bash
VAN=$(uuidgen); DRIVER=$(uuidgen); DA=$(uuidgen)
# VAN_LOAD (hub → van) — routing endpoint, needs the token
jpost "$BASE/routing/vans/$VAN/load-scan" -H "Authorization: Bearer $TOKEN" \
  -d "{\"loop_index\":0,\"parcel_ids\":[\"$SID\"],\"driver_id\":\"$DRIVER\"}"; echo
# VAN_TO_DA (van → DA) — telemetry door, no token needed
jpost "$BASE/api/v1/van/$VAN/telemetry" \
  -d "{\"type\":\"DELIVER\",\"lat\":19.07,\"lon\":72.87,\"parcel_id\":\"$SID\",\"da_id\":\"$DA\",\"driver_id\":\"$DRIVER\"}"; echo
sleep 1
# VAN rows present ⇒ adapter is wired (NoOp would log only, no row). "UNKNOWN_PARCEL" on telemetry is
# fine — no manifest binding, but the ledger row still lands.
psql_dev -c "SELECT scan_type, location_type, location_id AS van, actor_id AS driver, counterparty_id AS da
             FROM scan_ledger WHERE shipment_id='$SID' AND location_type='VAN';"
```

## 8. Immutability, dedup, and guard checks

```bash
# (a) Append-only trigger rejects mutation:
psql_dev -c "UPDATE scan_ledger SET scan_type='X' WHERE shipment_id='$SID';"   # ERROR: append-only
psql_dev -c "DELETE FROM scan_ledger WHERE shipment_id='$SID';"                # ERROR: append-only

# (b) REST dedup — same client_scan_id (fresh Idempotency-Key each) → one row.
#     Pre-position to DISPATCHED_TO_HUB so the single (deduped) HUB_DEST_IN transition is legal —
#     otherwise firing it while already at AT_DEST_HUB is an illegal transition and the state-change
#     event correctly dead-letters (the ledger row still lands; see the DLQ note below).
psql_dev -qc "UPDATE shipments SET state='DISPATCHED_TO_HUB'::shipment_state WHERE id='$SID';"
CS=$(uuidgen)
for i in 1 2; do jpost "$BASE/api/v1/scan" -o /dev/null -w "%{http_code} " \
  -d "{\"shipment_id\":\"$SID\",\"scan_type\":\"HUB_DEST_IN\",\"location_type\":\"HUB\",\"client_scan_id\":\"$CS\"}"; done; echo
psql_dev -c "SELECT count(*) FROM scan_ledger WHERE client_scan_id='$CS';"   # expect 1

# (c) Van dedup — re-fire VAN_TO_DA; synthetic key (shipment_id:scan_type) collapses to one row:
jpost "$BASE/api/v1/van/$VAN/telemetry" -o /dev/null \
  -d "{\"type\":\"DELIVER\",\"lat\":19.07,\"lon\":72.87,\"parcel_id\":\"$SID\",\"da_id\":\"$DA\",\"driver_id\":\"$DRIVER\"}"
psql_dev -c "SELECT scan_type, count(*) FROM scan_ledger WHERE shipment_id='$SID' AND scan_type='VAN_TO_DA' GROUP BY scan_type;"  # expect 1

# (d) Door guards → 400 (van type / LABEL_GENERATED / bogus rejected on the generic door):
for t in VAN_LOAD LABEL_GENERATED NOPE; do
  jpost "$BASE/api/v1/scan" -o /dev/null -w "$t=%{http_code}\n" -d "{\"shipment_id\":\"$SID\",\"scan_type\":\"$t\",\"location_type\":\"HUB\"}"
done
```

## 9. The full first→last trail (the station-manager query)

```bash
psql_dev -c "SELECT scan_type, location_type, scanned_at
             FROM scan_ledger WHERE shipment_id='$SID' ORDER BY scanned_at;"
```
Expected ordered trail (one immutable row each): `LABEL_GENERATED`(DA) → `HUB_ORIGIN_IN`(HUB) →
`HUB_ORIGIN_OUT`(HUB) → `GHA_ACCEPTANCE`(AIRPORT) → `DEST_SHUTTLE_IN`(SHUTTLE) → `HUB_DEST_IN`(HUB) →
`DELIVERED`(DA), plus the van rows `VAN_LOAD`/`VAN_TO_DA`(VAN).

## Pass criteria
- Full ordered scan trail present; every scan is one immutable row.
- `shipments.parcel_id` stamped from `LABEL_GENERATED`; the 5 owned scans advanced M4 state live (DLQ = 0).
- `DELIVERED` recorded but drove no transition (state stayed `AT_DEST_HUB`).
- VAN rows present (PR4 adapter wins over NoOp); van + REST replays each collapsed to one row.
- `UPDATE`/`DELETE` on `scan_ledger` rejected by the trigger; van + `LABEL_GENERATED` doors return 400.

> Notes:
> - §6 state pre-positioning stands in for the unbuilt M5/M7/M9 triggers on this branch — it exercises
>   only M8's owned transitions and the live consumer, not the other modules' logic.
> - **DLQ semantics (by design):** a scan whose M4 transition is illegal *from the shipment's current
>   state* still records its ledger row unconditionally — but the state-change event is rejected to
>   `oneday.scan.events.dlq` rather than corrupting state. So an *out-of-order* scan legitimately
>   grows the DLQ; only the **in-order** §6 walk should keep DLQ at 0. (`rabbitmqadmin -N <cfg> purge
>   queue name=oneday.scan.events.dlq` to clear leftovers between runs.)
> - Shared-broker tell (different problem): **in-order, legal** scans return 202 but state never
>   advances *and* the DLQ grows — another consumer is eating your `orders.scan` messages. See the
>   broker-isolation note up top.
