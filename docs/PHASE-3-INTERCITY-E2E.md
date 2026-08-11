# Phase 3 — Intercity end-to-end (the full air chain, DEL → BOM)

> The complete intercity run: first mile → origin hub → **flight booking (M9) + AWB** → air legs →
> dest hub → last mile. Extends [`PHASE-2A`](./PHASE-2A-HUB-RETURN-RUN.md) (first mile) and
> [`PHASE-2B`](./PHASE-2B-INTERCITY-POST-HUB.md) (post-hub chain + gaps). **Read "Readiness" first** —
> several steps are manual API calls today, not screen actions.

## What M9 (airline) does, in plain terms

M9 gets a sealed hub bag onto a plane and tracks it. Five moving parts:

1. **Schedule** — a monthly/weekly job pulls the real flight timetable (AeroDataBox, or a synthetic
   stand-in when the feed is off) into our `flight_leg` store. Reading it costs nothing.
2. **Assign** — the moment a parcel is sorted at the origin hub, M7 asks M9 "which flight?" M9 picks the
   cheapest flight that meets the 16h promise, **avoiding the 00:00–09:00 prime window (+35%)** and
   preferring a later flight to consolidate (one AWB per plane).
3. **Book** — when the hub **seals** that flight's bag, M9 books it as one AWB (a `flight_instance` +
   `awb` + per-parcel lines) and tells M4 each parcel is now on flight X.
4. **AWB from Bhagwati** — the consolidator hands us the **real** air waybill out-of-band; we stamp it
   onto the booking (admin API now; WhatsApp later). That number is what tracks the physical cargo.
5. **Track & disrupt** — a clock-based poll flips DEPARTED/LANDED (free) and moves the map dot by
   interpolation; a separate tiered poll checks the vendor for cancel/delay and **reassigns** to a
   rescue flight if the promise breaks.

## Readiness — what's a screen vs a manual API call (read first)

| Step | How it works today |
|---|---|
| Book, first mile, origin-hub scan-in, sort into flight bag | ✅ **Screens** (business portal, driver app, hub console) — proven in Phase 2a |
| Origin hub **seals** flight bag → M9 books AWB | ✅ **Screen** (hub console "Seal") → M9 books automatically |
| Enter the **real Bhagwati AWB** | ✅ **Screen** — airline console AWB page → "Save AWB" (placeholder → real). *(API: `POST /airline/flights/{flightNo}/{date}/awb`.)* |
| `HUB_ORIGIN_OUT`, `GHA_ACCEPTANCE`, `DEST_SHUTTLE_IN`, `HUB_DEST_IN` custody scans | ✅ **Screen** — airline console AWB page → **Airport custody** buttons (one AWB = whole plane). **G1 closed** |
| DEPARTED / LANDED | ✅ **Automatic** (M9 poll on the flight's time). For a quick test, fast-forward the flight time (SQL below) |
| Dest hub receive + sort | ✅ **Screen** (hub console, dest hub) |
| Dest → **delivery DA assignment** | ✅ **Wired** — dest sort emits `PARCEL_SORTED_FOR_DELIVERY` → M5 assigns the delivery DA (HUB_RETURN dest). **G4 verified**. *(VAN_MEETING dest uses the M6 van loop.)* |
| Last-mile deliver + recipient OTP → DROPPED | ✅ **Screen** (driver app) — OTP fixed in Part A |

**Bottom line:** now a **screens-only** intercity run (AWB entry + the four airport custody hand-offs are
buttons on the airline console AWB page; delivery assignment is automatic for a HUB_RETURN dest). The only
manual aid left is optionally **fast-forwarding the flight time** for a quick test instead of waiting for
the real departure.

## Credentials & URLs

| What | Value |
|---|---|
| Business portal (book) | b2b.demo@oneday.test / godspeed2026 |
| Driver app (pickup + delivery DA) | agniva.da@oneday.test / … |
| Hub console | godspeed-hub.vercel.app → admin@oneday.in / godspeed2026 (select **origin** then **dest** hub) |
| Admin (API / AWB / scans / OTP peek) | admin@oneday.in / godspeed2026 |
| Backend | https://one-day-delivery.onrender.com |

```bash
BASE=https://one-day-delivery.onrender.com
TOK=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" \
  -d '{"email":"admin@oneday.in","password":"godspeed2026"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
```

## Full intercity state chain

`BOOKED → PICKUP_ASSIGNED → PICKED_UP → HANDED_TO_PICKUP_VAN/RETURNED_TO_HUB → AT_ORIGIN_HUB →`
`ORIGIN_HUB_PROCESSING → IN_TAKEOFF_BAG → DISPATCHED_TO_AIRPORT → AT_AIRPORT → DEPARTED → LANDED →`
`DISPATCHED_TO_HUB → AT_DEST_HUB → DEST_HUB_PROCESSING → HANDED_TO_DROP_VAN → DROP_ASSIGNED →`
`DROP_COLLECTED → DROPPED`

## The run — step by step

### A. First mile → origin hub (identical to Phase 2a)
1. **Book** an **intercity** shipment (business portal), e.g. **DEL → BOM**. → `BOOKED`.
2. **Pickup** — DA online → auto-assigned → en route → arrived → scan → sender OTP → `PICKED_UP` → carry/van to origin hub → `AT_ORIGIN_HUB`.
3. **Origin-hub scan-in + sort** (hub console, DEL hub): receive → scan → the parcel sorts into the **flight bag** for the flight M9 assigned. → `ORIGIN_HUB_PROCESSING`. *(M9 chose the flight here — verify with `GET /airline/lanes/DEL/BOM/schedule?date=YYYY-MM-DD`.)*

### B. Book the flight + AWB
4. **Seal the flight bag** (hub console "Seal", or `POST /hub/{DEL_HUB_ID}/bags/{BAG_ID}/seal`). → M9 **books the AWB**. Verify:
   ```bash
   curl -s -H "Authorization: Bearer $TOK" "$BASE/airline/awb/by-bag/<BAG_ID>"   # → flightNo, flightDate, awbNo(placeholder), cost
   ```
5. **Enter the real Bhagwati AWB** (API — no UI yet). Use the flightNo/flightDate from step 4:
   ```bash
   curl -s -X POST -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
     "$BASE/airline/flights/<FLIGHT_NO>/<FLIGHT_DATE>/awb" -d '{"awb_no":"098-12345675"}'
   ```
6. *(Optional)* **Warehouse handoff timestamps:** `POST /airline/awb/{AWB_ID}/handed-over` then `/loaded`.

### C. Air legs (airline console — G1 closed — + auto flight flips)
On the **airline console** AWB page (the same page where you saved the AWB), the **Airport custody**
buttons fire each hand-off for every parcel on the AWB:
1. **Dispatched to airport** → `DISPATCHED_TO_AIRPORT`
2. **GHA accepted** → `AT_AIRPORT`
3. *(M9 auto-flips **DEPARTED** then **LANDED** on the flight's time — fast-forward below for a quick test)*
4. **Dest shuttle-in** → `DISPATCHED_TO_HUB`
5. **Received at dest hub** → `AT_DEST_HUB`

*(Equivalent API, if ever needed: `POST /airline/awb/{awbId}/{dispatched-to-airport|gha-accepted|dest-shuttle-in|dest-received}`.)*

### D. Dest hub → last mile
7. **Dest-hub receive + sort** (hub console, **BOM** hub): receive → sort → `DEST_HUB_PROCESSING`.
8. **Delivery assignment (G4):** the delivery DA is assigned when the parcel reaches `HANDED_TO_DROP_VAN`. If the dest sort doesn't emit that automatically, nudge it (seal/dispatch the dest delivery bag on the console, or ask me to push it) → M5 assigns → **DROP task** in the driver app.
9. **Deliver** (driver app): collect → en route → arrived → scan → **recipient OTP** (peek `GET /internal/dev/shipments/<REF>/delivery-otp`) → **`DROPPED`**. Done.

## Fast-forward the flight (for a quick test — no waiting hours)
DEPARTED/LANDED fire when the flight's scheduled time passes. To see them in minutes, set the booked
`flight_instance` times near-now, then wait for the 5-min poll (or it fires on the next tick):
```sql
-- against the app DB; FLIGHT_NO/FLIGHT_DATE from step 4
UPDATE flight_instance
   SET departure = now() + interval '2 min', arrival = now() + interval '4 min'
 WHERE flight_no = '<FLIGHT_NO>' AND flight_date = '<FLIGHT_DATE>';
```
Then the tracking page shows In-flight → Landed within ~5 min, and the dot interpolates DEL→BOM.

## Watch
Customer/business **track** page for the ref: milestones advance **Booked → Picked up → At hub →
In flight → Landed → Out for delivery → Delivered**; the dot follows the DA, then glides along the
flight path while airborne, then the delivery DA.

## Status of the earlier gaps
- **G1 — custody scans:** ✅ **closed** — airline console "Airport custody" buttons emit the four scans
  per-AWB (backend `CustodyScanProducer` → `oneday.scan.events` → M4).
- **AWB admin button:** ✅ **built** — "Save AWB" on the airline console AWB page.
- **G4 — intercity delivery hand-off:** ✅ **verified wired** for a HUB_RETURN dest (dest sort →
  `PARCEL_SORTED_FOR_DELIVERY` → M5 `assignDelivery` → `HUB_DELIVERY_ASSIGNED`). VAN_MEETING dest goes
  through the M6 van loop (separate).
- **DELAYED/CANCELLED live check** (still open): reassignment is unit-tested; force a real cancelled
  flight once the paid AeroDataBox tier is on to see it end-to-end.
