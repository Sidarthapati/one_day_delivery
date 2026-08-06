# M9 (airline) end-to-end demo

A plain HTML/CSS/JS console (no build step) for manually walking through the entire M9 lifecycle:
booking a real shipment, receiving/bagging/sealing it at a hub, watching M9 book it, ground-crew
confirmations, and live tracking with a moving plane marker.

## Why this isn't a standalone app

The airline/hub endpoints aren't CORS-enabled (only `/api/**` and `/internal/**` are). Rather than
loosen that, this folder is served **same-origin** by the assembled Spring app, exactly like the old
M1/M4 demo did.

## Running it

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # JDK 21 required
cp -r demo/* app/src/main/resources/static/demo/
set -a; source .env; set +a                      # Render DB + CloudAMQP credentials
mvn spring-boot:run -pl app
```

Then open **http://localhost:8080/demo/**. Requires internet access once, to fetch Leaflet (map
library) and OpenStreetMap tiles from their CDNs on the tracking page — everything else is local.

To pick up an edit without a full backend restart, re-copy just the changed file(s) into
`app/target/classes/static/demo/` (same hot-reload trick the old demo's dev workflow used).

## The flow (numbered to match the nav links)

1. **Register or log in** (`index.html`) — one plain customer account drives the whole thing.
   Self-registration returns a token immediately (no approval queue).
2. **Book** (`booking.html`) — creates a real B2C shipment (self-drop / hub-collect / COD, so
   there's no DA-pickup or payment-gateway step to simulate) and immediately calls a small
   confirm-self-drop endpoint right after. That second call exists because a fresh booking only
   ever reaches `BOOKED` — nothing in the product currently drives a self-drop shipment any
   further (unlike DA_PICKUP, which M5 does automate, but needs a real serviceable DA and none
   exist in this DB). Since pickup mechanics aren't what this demo is testing, `confirm-self-drop`
   (`B2cShipmentController`) is a deliberate, minimal stand-in for that one missing trigger — it
   just calls the real state machine's already-registered `BOOKED → AWAITING_SELF_DROP` edge.
3. **Hub — receive** (`hub.html`) — the moment M9's flight-assignment logic actually runs; shows
   the auto-picked flight and bag straight away.
4. **Hub — add to bag** (`hub.html`) — receiving only opens/finds the bag, it doesn't add the
   parcel to it; this step does.
5. **Hub — seal** (`hub.html`, in the bags table) — the real trigger M9 books off of.
6. **Airline — schedule & simulated status** (`airline.html`) — see which flights on a lane are
   already going to be delayed/cancelled (the simulated outcome is deterministic per flight+date),
   useful for knowing what to expect before/after sealing.
7. **Airline — AWB & ground actions** (`airline.html`) — look up the AWB by bag ID (booking is
   asynchronous — if you get a 404 right after sealing, wait a couple of seconds and retry), then
   record hand-over/loaded confirmations.
8. **Tracking** (`tracking.html`) — polls the customer tracking endpoint. State label always
   updates for real; a moving plane marker appears once the parcel is actually airborne. If the
   flight you booked onto was flagged delayed/cancelled in step 6, this is also where you'd
   eventually see the AWB get superseded — that only happens on the real 5-minute background job's
   own schedule, not on demand.

## Notes

- Session (token, shipment ref, hub/bag ids) is kept in `sessionStorage`, not a JS variable —
  needed because this is several separate pages, not one single-page app.
- The 5 city/hub UUIDs in `js/cities.js` are hand-copied from `app/src/main/resources/application.yml`
  (`grid.cities`/`airline.cities`) — there's no API to fetch them, so keep both in sync if they
  ever change.
- All request bodies and response fields are **snake_case** (the app's global Jackson config) —
  confirmed live; a camelCase request body silently binds nothing and every field comes back
  "must not be blank".
- `confirm-self-drop` is a deliberate, narrowly-scoped stand-in (see step 2 above), not a real
  product feature — a real self-drop confirmation would likely be a QR/barcode scan-in at the hub
  dock, not a raw customer-triggered API call. Fine for testing M9, which only cares about what
  happens after a parcel reaches the hub.
