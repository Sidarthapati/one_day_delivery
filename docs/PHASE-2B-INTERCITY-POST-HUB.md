# Phase 2b — Intercity post-hub flow (origin hub → flight → dest hub → delivery)

> Companion to [`PHASE-2A-HUB-RETURN-RUN.md`](./PHASE-2A-HUB-RETURN-RUN.md). Phase 2a proved the
> **first mile → origin hub** (pickup → hub scan-in → flight bag). This card covers everything **after
> the origin hub** for a true **intercity** parcel (e.g. HYD → DEL): the air legs and the destination
> last mile. It documents the intended chain, what's actually wired today, the seams that still need a
> hand (G1–G5), and the **manual M8 scan-API calls** that force an intercity run end-to-end right now.

## How intercity differs from the same-city (#00002) run

A same-city parcel **collapses the air legs** — no flight, straight from origin-hub sort into a delivery
bag. An intercity parcel runs the **full chain**: origin-hub sort → takeoff bag → **airport → flight →
destination airport** → destination-hub sort → last mile. The origin half (steps 0–7 in Phase 2a) is
**identical**; this card is the **post-hub half**.

## Full intercity state chain & what drives each hop

| # | State | Driver | Event → M4 consumer | Wired? |
|---|-------|--------|---------------------|--------|
| — | `AT_ORIGIN_HUB` | M8 scan / M5 seam | `HUB_ORIGIN_IN` → `ScanEventsConsumer` | ✅ (Phase 2a) |
| 1 | `ORIGIN_HUB_PROCESSING` | M7 hub | `STAND_ASSIGNED` (per-parcel) → `HubEventsConsumer` | ✅ |
| 2 | `IN_TAKEOFF_BAG` | M7 hub | `BAG_CREATED` → `HubEventsConsumer` | ⚠️ **G2** (no shipmentId) |
| 3 | `DISPATCHED_TO_AIRPORT` | M8 scan | `HUB_ORIGIN_OUT` → `ScanEventsConsumer` | ⚠️ **G1** (no emitter) |
| 4 | `AT_AIRPORT` | M8 scan | `GHA_ACCEPTANCE` → `ScanEventsConsumer` | ⚠️ **G1** (no emitter) |
| 5 | `DEPARTED` | **M9 flight** | `DEPARTED` → `FlightEventsConsumer` | ✅ (poll job, clock-based) |
| 6 | `LANDED` | **M9 flight** | `LANDED` → `FlightEventsConsumer` | ✅ (poll job, clock-based) |
| 7 | `DISPATCHED_TO_HUB` | M8 scan | `DEST_SHUTTLE_IN` → `ScanEventsConsumer` | ⚠️ **G1** (no emitter) |
| 8 | `AT_DEST_HUB` | M8 scan | `HUB_DEST_IN` → `ScanEventsConsumer` | ⚠️ **G1** (no emitter) |
| 9 | `DEST_HUB_PROCESSING` | M7 hub | `DEST_SORT_COMPLETE` (per-parcel) → `HubEventsConsumer` | ✅ |
| 10 | **delivery assignment** | M7 → M5 | `HANDED_TO_DROP_VAN` (VAN) / `DROP_ASSIGNED` (both) | ⚠️ **G4** (intercity path) |
| 11 | `DROPPED` | M5 + delivery OTP | `DROP_COLLECTED`/`COLLECTED_FROM_HUB` → OTP verify | ✅ (**G5 fixed** — Part A) |

Full sequence (INTERCITY, VAN_MEETING dest):
`… AT_ORIGIN_HUB → ORIGIN_HUB_PROCESSING → IN_TAKEOFF_BAG → DISPATCHED_TO_AIRPORT → AT_AIRPORT →`
`DEPARTED → LANDED → DISPATCHED_TO_HUB → AT_DEST_HUB → DEST_HUB_PROCESSING → HANDED_TO_DROP_VAN →`
`DROP_ASSIGNED → DROP_COLLECTED → DROPPED`

For a **HUB_RETURN** destination city the tail is `DEST_HUB_PROCESSING → HUB_DELIVERY_ASSIGNED →
COLLECTED_FROM_HUB → DROPPED` (same as the same-city delivery half).

## What's confirmed wired

- **M9 auto-flips `DEPARTED`/`LANDED`** — `FlightStatusPollJob` (@5 min) flips a booked flight the moment
  its departure/arrival instant passes and notifies every parcel on the AWB. **Clock-based, zero vendor
  calls.**
- **M7 per-parcel hub milestones** — `STAND_ASSIGNED → ORIGIN_HUB_PROCESSING` and
  `DEST_SORT_COMPLETE → DEST_HUB_PROCESSING`.
- **All three M4 consumers are live** — `HubEventsConsumer`, `ScanEventsConsumer`, `FlightEventsConsumer`.
- **Live flight position** — `FlightTrackingPortAdapter` interpolates a straight-line dot between the two
  airports over the scheduled flight time (free, no ADS-B vendor).

## The gaps to close (or work around) before a clean intercity E2E

- **G1 — the four intercity custody scans have no automated emitter.** `HUB_ORIGIN_OUT`, `GHA_ACCEPTANCE`,
  `DEST_SHUTTLE_IN`, `HUB_DEST_IN` are consumed by `ScanEventsConsumer` but **nothing produces them** —
  they only exist as a physical scan-gun hitting `POST /api/v1/scan`. In the **Bhagwati** model the
  airport-side actor (GHA) is the non-tech freight consolidator, so `GHA_ACCEPTANCE` / `HUB_ORIGIN_OUT`
  won't be scanned by them. **Workaround today: POST them manually** (commands below). **Fix:** the M9
  delta reframes these — origin-hub-out + warehouse handoff become the M9 `AwbGroundService` timestamps;
  `GHA_ACCEPTANCE` is retired (Bhagwati owns the airport). Until then they stay manual.
- **G2 — `IN_TAKEOFF_BAG` carries no per-parcel id.** `HubEventsConsumer` maps `BAG_CREATED →
  IN_TAKEOFF_BAG` but `BAG_CREATED` is bag-level (null `shipmentId`) so it's skipped — the parcel never
  actually enters `IN_TAKEOFF_BAG`, and `HUB_ORIGIN_OUT → DISPATCHED_TO_AIRPORT` becomes an illegal jump
  from `ORIGIN_HUB_PROCESSING`. Needs a **per-parcel "sorted into flight bag"** seam (or post a per-parcel
  scan). Verify against `TransitionRegistry` before the run.
- **G3 — the parcel must be bound to a flight instance for `DEPARTED`/`LANDED` to fire.** `FlightStatusPollJob`
  only notifies parcels on a **booked AWB** whose `flight_instance` exists. Confirm the M7 bag-seal →
  M9 `HubEventConsumer.onBagSealed` → `AwbBookingService.book` chain actually ran for the test bag (check
  `GET /airline/awb/by-bag/{bagId}` returns a BOOKED AWB with a flight instance).
- **G4 — intercity HUB_RETURN delivery assignment.** Same-city reaches the delivery DA via
  `SAMECITY_OUTBOUND → HANDED_TO_DROP_VAN → assignDelivery`. For an **intercity HUB_RETURN destination**
  the parcel sits at `DEST_HUB_PROCESSING`; confirm M5 assigns a delivery DA on that path (else it stalls
  after the dest-hub sort). VAN_MEETING dest uses the (deprecated) `DROP_VAN_HANDOFF` / M6 loop.
- **G5 — delivery OTP.** ✅ **Fixed in Part A**: the OTP is minted when the parcel goes out for delivery
  (`DROP_COLLECTED`/`COLLECTED_FROM_HUB`), the verify/resend gate accepts both, and there's a dev peek at
  `GET /internal/dev/shipments/{ref}/delivery-otp`.

## Force an intercity run today — manual M8 scan-API fallback

`POST /api/v1/scan` records a lifecycle scan and fans a `ScanEvent` to M4 (the same door a hub scan-gun
uses). Use it to supply the four G1 scans (and, if G2 bites, a per-parcel takeoff scan) at each physical
checkpoint. Admin token as in Phase 2a:

```bash
BASE=https://one-day-delivery.onrender.com
TOK=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" \
  -d '{"email":"admin@oneday.in","password":"godspeed2026"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")

# helper: post one scan for a shipment (SHIP_ID = the shipment UUID, not the ref)
scan() {  # usage: scan <SCAN_TYPE>
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/api/v1/scan" \
    -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
    -d "{\"shipmentId\":\"$SHIP_ID\",\"scanType\":\"$1\",\"locationType\":\"HUB\",\"actorId\":\"$SHIP_ID\"}"
}

SHIP_ID=<shipment-uuid>
scan HUB_ORIGIN_OUT     # → DISPATCHED_TO_AIRPORT
scan GHA_ACCEPTANCE     # → AT_AIRPORT   (then M9 auto-flips DEPARTED → LANDED on the flight clock)
scan DEST_SHUTTLE_IN    # → DISPATCHED_TO_HUB   (after LANDED)
scan HUB_DEST_IN        # → AT_DEST_HUB   (then M7 DEST_SORT_COMPLETE → DEST_HUB_PROCESSING)
```

Between `AT_AIRPORT` and `DEST_SHUTTLE_IN`, leave a gap for `FlightStatusPollJob` to flip `DEPARTED`
then `LANDED` (it fires on the booked flight's scheduled departure/arrival instant; a demo/near-term
flight lands quickly). Check progress on the customer **track** page or
`GET /api/v1/shipments/mine/{ref}/track`.

## Where the M9 delta takes this next (Bhagwati model)

The intercity air legs above are the exact thing the **M9 targeted delta** makes real (see
`~/.claude/plans/cozy-forging-mccarthy.md` / project CLAUDE.md):
- **`GHA_ACCEPTANCE` retired; Bhagwati warehouse handoff** captured as `AwbGroundService.handOver` /
  `markLoaded` timestamps — the airport-side scans (G1) stop being our job.
- **Real schedule + daily status via AeroDataBox** (env-gated) replace the synthetic consolidator legs;
  cancellations/delays drive the existing `FlightReassignmentService`.
- **Real AWB number** entered by admin (later WhatsApp) replaces the synthetic `AWB-…`.
- **Prime-rate avoidance** (00:00–09:00 = +35%) + **leeway batching** shape which flight the bag books.

## Delivery-side stalls
- Stuck at `ORIGIN_HUB_PROCESSING` → G2: `IN_TAKEOFF_BAG` never applied; post a per-parcel takeoff scan or
  confirm the per-parcel seam.
- Never `DEPARTED` → G3: no booked AWB/flight instance for the bag (`GET /airline/awb/by-bag/{bagId}`).
- Stuck at `DEST_HUB_PROCESSING` → G4: no delivery DA assigned on the intercity path; check M5 or push via
  the dispatch API (as in Phase 2a step B).
- Recipient-OTP "wrong code" → should be fixed (G5, Part A); if still failing, peek
  `GET /internal/dev/shipments/{ref}/delivery-otp` and confirm the parcel is `DROP_COLLECTED` /
  `COLLECTED_FROM_HUB`.
