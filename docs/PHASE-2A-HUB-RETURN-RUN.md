# Phase 2a — HUB_RETURN live run (Hyderabad)

> Ready-to-run card for the field test. Verified 2026-08-09 against staging (backend `ce3c026`).

## Credentials & URLs
| What | Value |
|---|---|
| Driver app (Agniva = DA) | `agniva.da@oneday.test` / `2DGykZBwunW5` |
| Business portal (booking) | `b2b.demo@oneday.test` / `godspeed2026` |
| Hub console | **godspeed-hub.vercel.app** → `admin@oneday.in` / `godspeed2026` → select hub **HYD — Hyderabad** |
| Admin (API / OTP peek) | `admin@oneday.in` / `godspeed2026` |
| Backend | `https://one-day-delivery.onrender.com` |
| Pickup | California Burrito, Rajpurohit Tower, Nanakramguda (~17.4155, 78.3428) |
| Hub | Vajra Jasmine County, Financial District (~17.41397, 78.34436) — ~1 km from pickup |

## Verified ready
- Backend live (`ce3c026`): OTP peek + startup roster-load deployed.
- Agniva is in the roster for the pickup/hub hex (OFFLINE now → IDLE on login).
- Hyderabad = **HUB_RETURN**; pickup + hub both serviceable (same hex).
- B2B booking works; hub scan-gun autofocus deployed.

## The run — in order
- **0. Agniva online FIRST.** Log into the driver app, leave it open so GPS pings → flips **OFFLINE→IDLE (assignable)**. *If offline, the pickup defers `NO_DA_AVAILABLE`.*
- **1. Book.** Business portal → intercity **HYD → DEL**, pickup pin at California Burrito. Ensure wallet has balance. → `BOOKED`.
- **2. Auto-assign.** M5 assigns pickup to Agniva → **PICKUP task** appears. → `PICKUP_ASSIGNED` (OTP minted).
- **3. Label.** Shipment → label page → print Code128 → tape to the box → place box at the pickup point.
- **4. Pickup.** Agniva: **En route** (`IN_PROGRESS`, GPS) → drive → **Mark arrived** → **camera-scan** the label.
- **5. OTP.** Fetch on the hub laptop, read to Agniva → **Confirm pickup** → `PICKED_UP`:
  ```bash
  BASE=https://one-day-delivery.onrender.com
  TOK=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" \
    -d '{"email":"admin@oneday.in","password":"godspeed2026"}' \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
  curl -s -H "Authorization: Bearer $TOK" "$BASE/internal/dev/shipments/<SHIPMENT_REF>/pickup-otp"
  ```
- **6. Carry to hub.** Agniva drives to Vajra Jasmine County → **"Hand off at hub"** → `RETURNED_TO_HUB` → `AT_ORIGIN_HUB`.
- **7. Hub scan-in (finish).** Hub console → **Receive** → click "Scan one parcel" → **scan with the gun** → parcel sorts into the **DEL flight bag** (verify on **Bags** page: bag + stand). → `ORIGIN_HUB_PROCESSING`. **← Phase-2a done.**

## Throughout
Watch the customer **track** page for the ref: Agniva's live dot follows his GPS; milestones advance **Booked → Picked up → At hub**.

## State chain
`BOOKED → PICKUP_ASSIGNED → PICKED_UP → RETURNED_TO_HUB → AT_ORIGIN_HUB → ORIGIN_HUB_PROCESSING (flight bag)`

> **Intercity?** Everything *after* the origin hub (flight bag → airport → flight → dest hub → delivery)
> is in [`PHASE-2B-INTERCITY-POST-HUB.md`](./PHASE-2B-INTERCITY-POST-HUB.md).

## If something stalls
- No task after booking → confirm Agniva is **online/IDLE** and it's within SHIFT_2 (14:00–22:00 IST). Re-load roster: `POST /dispatch/admin/shift-load?shift=SHIFT_2` (admin token).
- OTP 404 → the pickup isn't `PICKUP_ASSIGNED` yet.
- Hub receive error → confirm the shipment reached `AT_ORIGIN_HUB` (hub-handoff done) and hub **HYD** is selected.

---

# Phase 2b — same-city HUB_RETURN delivery (#00002, HYD → HYD)

A same-city parcel **collapses the air legs** — no flight, no dest hub hop. After the pickup + hub drop
it's sorted straight into a **destination territory (delivery) bag** at a stand, then a delivery DA
collects it **from the hub** and runs the last mile. First mile (steps 0–7 above) is identical; this
section is the **delivery half**.

## Full state chain (same-city, HUB_RETURN)
`BOOKED → PICKUP_ASSIGNED → PICKED_UP → RETURNED_TO_HUB → AT_ORIGIN_HUB → ORIGIN_HUB_PROCESSING →`
`IN_TAKEOFF_BAG → HANDED_TO_DROP_VAN → HUB_DELIVERY_ASSIGNED → COLLECTED_FROM_HUB → DROPPED`

## Done so far for #00002
Pickup → hub drop → **received + sorted at the hub → delivery territory bag + stand assigned**
(≈ `ORIGIN_HUB_PROCESSING`). The parcel is physically at the hub, staged for its territory.

## Next steps to see it delivered
- **A. Dispatch the same-city bag (hub console).** Seal / close the same-city **delivery bag** on the hub
  console. This emits `SAMECITY_OUTBOUND` → **`HANDED_TO_DROP_VAN`** — the exact state M5 listens on to
  create a delivery task. *(If the console has no "seal/dispatch same-city bag" action wired, tell me and
  I'll nudge it via API — the goal is to reach `HANDED_TO_DROP_VAN`.)*
- **B. Auto-assign delivery.** M5 (`HANDED_TO_DROP_VAN` + `DA_DELIVERY` → `assignDelivery`) assigns the
  parcel to the DA covering the dest territory (Agniva) → a **DELIVERY task** appears in the driver app →
  **`HUB_DELIVERY_ASSIGNED`**. *Agniva must be online/IDLE — same roster rule as pickup; re-load with
  `POST /dispatch/admin/shift-load?shift=SHIFT_2` if the task doesn't show.*
- **C. Collect from hub.** Driver app → open the DELIVERY task → **"Collect from hub"** (the HUB_RETURN
  RECEIVE step) → records hub-collect → **`COLLECTED_FROM_HUB`** (+ hub-dest custody scan).
- **D. Deliver.** **En route** (GPS) → drive to the recipient → **Mark arrived** → **scan the parcel**.
- **E. Confirm → delivered.** Recipient OTP → **`DROPPED`** (+ COD if any). End-to-end done.

## Step E (recipient OTP) — FIXED (was a blocker)
The last-mile **delivery OTP now works** for HUB_RETURN. The OTP is minted the moment the parcel goes out
for delivery (`COLLECTED_FROM_HUB`), and the verify/resend endpoint accepts both `DROP_COLLECTED` and
`COLLECTED_FROM_HUB`. Peek the code on the hub laptop, read it to the DA, and the in-app OTP step drives
`COLLECTED_FROM_HUB → DROPPED`:
```bash
curl -s -H "Authorization: Bearer $TOK" "$BASE/internal/dev/shipments/<SHIPMENT_REF>/delivery-otp"
```
*(TTL is 30 min; if it lapses on a long drive, the DA taps "Resend OTP" and re-peek.)*

**To still see `DROPPED` (Delivered) today — workaround, no app change:** after step C (task is
IN_PROGRESS / `COLLECTED_FROM_HUB`), complete it via the dispatch API — this bypasses the OTP screen and
drives the shipment to delivered:
```bash
BASE=https://one-day-delivery.onrender.com
TOK=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" \
  -d '{"email":"admin@oneday.in","password":"godspeed2026"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
# Agniva's DA id = a0bd9ada-b181-4618-83c9-335a550836a4 ; use the DELIVERY task id from GET /dispatch/da/{daId}/tasks
curl -s -X POST -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
  "$BASE/dispatch/da/<DA_ID>/tasks/<DELIVERY_TASK_ID>/drop-completed" \
  -d '{"cod_collected": false}'
```
→ `DROP_COMPLETED` → **`DROPPED`**; the track page shows **Delivered**.

**Shipped** (backend Part A): the delivery OTP is minted on out-for-delivery, verify/resend accept
`COLLECTED_FROM_HUB`, and the dev peek above mirrors the pickup OTP. The `drop-completed` call above
remains a valid manual fallback if you ever want to bypass the OTP screen.

## Watch
Business/customer **track** for `1DD-HYD-20260809-00002`: milestones advance **At hub → Out for delivery →
Delivered**; Agniva's live dot follows his GPS on the delivery leg (needs backend PR #88 deployed so the
dot reads the live ping trail instead of flipping to the pickup).

## Delivery-side stalls
- No DELIVERY task after step A → parcel didn't reach `HANDED_TO_DROP_VAN` (bag not dispatched), or Agniva
  isn't online/IDLE, or the dest hex isn't in his territory.
- Hub-collect 409 → the parcel isn't `HUB_DELIVERY_ASSIGNED` yet (step B not done), or HYD isn't HUB_RETURN.
- Recipient-OTP "wrong code" → the known blocker above; use the `drop-completed` workaround.
