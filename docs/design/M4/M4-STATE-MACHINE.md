# M4 — Shipment State Machine

> Extracted from [M4-ORDERS-DESIGN.md](M4-ORDERS-DESIGN.md) §6.  
> Update this file whenever the state machine changes. Ops sign-off required before implementation.

---

## Visual Flow

```
                         ┌─────────┐
                         │ BOOKED  │
                         └────┬────┘
              ┌───────────────┼──────────────────┐
              ▼               ▼                  │
     ┌────────────────┐  ┌──────────┐            │
     │PICKUP_ASSIGNED │  │CANCELLED │◄───────────┤ (customer cancels — policy TBD, see BD-001)
     └───────┬────────┘  └──────────┘            │
      ┌──────┴──────┐                            │
      ▼             ▼                            │
┌─────────────┐ ┌──────────────┐                │
│PICKUP_FAILED│ │  PICKED_UP   │────────────────►┘
└──────┬──────┘ └──────┬───────┘
       │               │
       │          ┌────▼──────────┐
       │          │ HANDED_TO_VAN │   DA cron handoff; DA responsibility ends
       │          └────┬──────────┘
       │               │
       │          ┌────▼──────────┐
       │          │AT_ORIGIN_HUB  │   Hub in-scan (M8)
       │          └────┬──────────┘
       │               │
       │          ┌────▼──────────┐
       │          │HUB_PROCESSING │   Stand assigned; being sorted (M7)
       │          └────┬──────────┘
       │               │
       │          ┌────▼──────────┐
       │          │    IN_BAG     │   Bagged for specific flight (M7)
       │          └────┬──────────┘
       │               │
       │     ┌─────────┴──────────────────────────────────────┐
       │     │ delivery_type=INTERCITY      delivery_type=SAME_CITY
       │     ▼                                        ▼
       │  ┌────────────────────┐            ┌──────────────────────┐
       │  │DISPATCHED_TO_AIRPORT│           │  OUT_FOR_DELIVERY     │ (skip air leg)
       │  └────┬───────────────┘            └──────────┬───────────┘
       │       │                                        │
       │  ┌────▼──────────┐                            │
       │  │  AT_AIRPORT   │   GHA acceptance scan       │
       │  └────┬──────────┘                            │
       │       │                                        │
       │  ┌────▼──────────┐                            │
       │  │   DEPARTED    │   Flight departed            │
       │  └────┬──────────┘                            │
       │       │                                        │
       │  ┌────▼──────────┐                            │
       │  │ AT_DEST_HUB   │   Dest hub in-scan (M8)     │
       │  └────┬──────────┘                            │
       │       │                                        │
       │  ┌────▼──────────────┐                        │
       │  │DEST_HUB_PROCESSING│   Last-mile sort         │
       │  └────┬──────────────┘                        │
       │       │                                        │
       │  ┌────▼──────────────┐                        │
       │  │ OUT_FOR_DELIVERY  │◄───────────────────────┘
       │  └────┬──────────────┘
       │  ┌────┴─────────────┐
       │  ▼                  ▼
       │ ┌──────────┐ ┌─────────────────┐
       │ │DELIVERED │ │ DELIVERY_FAILED  │
       │ └──────────┘ └────────┬────────┘
       │              ┌────────┴──────────────┐
       │              ▼                       ▼
       │    ┌─────────────────┐   ┌──────────────────────┐
       │    │  RTO_INITIATED  │   │  OUT_FOR_DELIVERY     │ (rescheduled attempt)
       │    └──────┬──────────┘   └──────────────────────┘
       │           │
       │    ┌──────▼──────────┐
       │    │ RTO_IN_TRANSIT  │
       │    └──────┬──────────┘
       │           │
       └──────────►│
                   ▼
            ┌──────────────┐
            │ RTO_COMPLETED│
            └──────────────┘
```

---

## States Reference

| # | State | Meaning | Custody | Triggered by |
|---|---|---|---|---|
| 1 | `BOOKED` | Created; payment captured (B2C/C2C PREPAID) or COD accepted or invoiced (B2B) | Platform | M4 booking API |
| 2 | `PICKUP_ASSIGNED` | DA assigned to collect | DA | M5 `oneday.da.assigned` |
| 3 | `PICKED_UP` | DA confirmed physical pickup | DA | M5 `oneday.da.pickup_completed` |
| 4 | `HANDED_TO_VAN` | DA handed to cron van; DA responsibility ends | Cron van | M5 `oneday.da.cron_handoff_completed` |
| 5 | `AT_ORIGIN_HUB` | Scanned in at origin hub | Hub ops | M8 `HUB_ORIGIN_IN` scan event |
| 6 | `HUB_PROCESSING` | Stand assigned; being sorted | Hub ops | M7 stand assignment event |
| 7 | `IN_BAG` | Bagged for specific flight (or same-city route) | Hub ops | M7 bag creation event |
| 8 | `DISPATCHED_TO_AIRPORT` | Bag on cron van; left the hub *(INTERCITY only)* | Cron driver | M6/M7 cron departure event |
| 9 | `AT_AIRPORT` | Handed to GHA; airline custody *(INTERCITY only)* | GHA/Airline | M8 `GHA_ACCEPTANCE` scan |
| 10 | `DEPARTED` | Flight departed *(INTERCITY only)* | Airline | M9 `flight.departed` event |
| 11 | `AT_DEST_HUB` | Scanned in at destination hub *(INTERCITY only)* | Dest hub ops | M8 `HUB_DEST_IN` scan |
| 12 | `DEST_HUB_PROCESSING` | Last-mile sort at destination *(INTERCITY only)* | Dest hub ops | M7 dest sort event |
| 13 | `OUT_FOR_DELIVERY` | Last-mile DA assigned and en route | Last-mile DA | M5 `oneday.da.lastmile_assigned` |
| 14 | `DELIVERED` | Delivery confirmed | — (complete) | M5 `oneday.da.delivery_completed` |
| — | `PICKUP_FAILED` | DA could not pick up | — | M5 `oneday.da.pickup_failed` |
| — | `DELIVERY_FAILED` | DA could not deliver | — | M5 `oneday.da.delivery_failed` |
| — | `RTO_INITIATED` | Return-to-origin triggered | Platform | M11 after N failed delivery attempts |
| — | `RTO_IN_TRANSIT` | Return flight to origin city *(INTERCITY only)* | Airline | M9 return flight departed |
| — | `RTO_COMPLETED` | Returned to sender | — (complete) | M5 return delivery confirmed |
| — | `CANCELLED` | Cancelled by customer | — (complete) | M4 cancellation API |

States 8–12 are skipped for `SAME_CITY` shipments. `IN_BAG` transitions directly to `OUT_FOR_DELIVERY`.

---

## Allowed Transitions

```
BOOKED
  → PICKUP_ASSIGNED           (M5: oneday.da.assigned)
  → CANCELLED                 (API: customer cancels — see BD-001 for policy)

PICKUP_ASSIGNED
  → PICKED_UP                 (M5: oneday.da.pickup_completed)
  → PICKUP_FAILED             (M5: oneday.da.pickup_failed)
  → CANCELLED                 (API: customer cancels — see BD-001)

PICKED_UP
  → HANDED_TO_VAN             (M5: oneday.da.cron_handoff_completed)
  → CANCELLED                 (API: current assumed last state — see BD-001)

HANDED_TO_VAN
  → AT_ORIGIN_HUB             (M8: HUB_ORIGIN_IN scan)

AT_ORIGIN_HUB
  → HUB_PROCESSING            (M7: stand assignment event)

HUB_PROCESSING
  → IN_BAG                    (M7: bag creation event)

IN_BAG [delivery_type=INTERCITY]
  → DISPATCHED_TO_AIRPORT     (M6/M7: cron departure event)

IN_BAG [delivery_type=SAME_CITY]
  → OUT_FOR_DELIVERY          (M5: lastmile_assigned)

DISPATCHED_TO_AIRPORT
  → AT_AIRPORT                (M8: GHA_ACCEPTANCE scan)

AT_AIRPORT
  → DEPARTED                  (M9: flight.departed event)

DEPARTED
  → AT_DEST_HUB               (M8: HUB_DEST_IN scan)

AT_DEST_HUB
  → DEST_HUB_PROCESSING       (M7: dest sort event)

DEST_HUB_PROCESSING
  → OUT_FOR_DELIVERY          (M5: lastmile_assigned)

OUT_FOR_DELIVERY
  → DELIVERED                 (M5: delivery_completed)
  → DELIVERY_FAILED           (M5: delivery_failed)

DELIVERY_FAILED
  → RTO_INITIATED             (M11: after N failed attempts)
  → OUT_FOR_DELIVERY          (M11: rescheduled delivery)

PICKUP_FAILED
  → PICKUP_ASSIGNED           (M11: rescheduled pickup)
  → CANCELLED                 (M11: no further pickup possible)

RTO_INITIATED
  → RTO_IN_TRANSIT            (M9: return flight departed)

RTO_IN_TRANSIT
  → RTO_COMPLETED             (M5: return delivery to sender confirmed)
```

Any transition not listed above is rejected with `409 Conflict`.

---

## Customer-Visible State Labels

| State | Label shown to customer |
|---|---|
| `BOOKED` | Order confirmed |
| `PICKUP_ASSIGNED` | Pickup agent assigned |
| `PICKED_UP` | Parcel collected |
| `HANDED_TO_VAN` | Parcel handed to transport |
| `AT_ORIGIN_HUB` | Arrived at origin hub |
| `HUB_PROCESSING` | Being processed at hub |
| `IN_BAG` | Sorted and bagged for dispatch |
| `DISPATCHED_TO_AIRPORT` | En route to airport |
| `AT_AIRPORT` | At airport — airline check-in |
| `DEPARTED` | In transit by air |
| `AT_DEST_HUB` | Arrived at destination hub |
| `DEST_HUB_PROCESSING` | Being sorted for last-mile |
| `OUT_FOR_DELIVERY` | Out for delivery |
| `DELIVERED` | Delivered |
| `PICKUP_FAILED` | Pickup unsuccessful |
| `DELIVERY_FAILED` | Delivery unsuccessful |
| `RTO_INITIATED` | Return to sender initiated |
| `RTO_IN_TRANSIT` | Returning to sender |
| `RTO_COMPLETED` | Returned to sender |
| `CANCELLED` | Cancelled |
