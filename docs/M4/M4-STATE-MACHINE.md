# M4 — Shipment State Machine

> Extracted from [M4-ORDERS-DESIGN.md](M4-ORDERS-DESIGN.md) §6.  
> Update this file whenever the state machine changes. Ops sign-off required before implementation.

---

## Visual Flow

```
                            ┌─────────┐
                            │ BOOKED  │
                            └────┬────┘
               ┌────────────────┼───────────────────────────┐
               ▼                ▼                           │
   ┌──────────────────┐  ┌──────────┐                       │
   │ PICKUP_ASSIGNED  │  │CANCELLED │◄──────────────────────┤ (see BD-001)
   └────────┬─────────┘  └──────────┘                       │
    ┌───────┴────────┐                                       │
    ▼                ▼                                       │
┌──────────────┐ ┌──────────────┐                           │
│PICKUP_FAILED │ │  PICKED_UP   │───────────────────────────►┘
│  (→ M11)     │ └──────┬───────┘
└──────────────┘        │
                   ┌────▼───────────────────┐
                   │  HANDED_TO_PICKUP_VAN  │  DA cron handoff; DA responsibility ends
                   └────┬───────────────────┘
                        │
                   ┌────▼───────────────┐
                   │  AT_ORIGIN_HUB     │  Hub in-scan (M8); EtaPort.fetchEta called
                   └────┬───────────────┘
                        │
                   ┌────▼───────────────────────┐
                   │  ORIGIN_HUB_PROCESSING     │  Stand assigned; being sorted (M7)
                   └────┬───────────────────────┘
                        │
                   ┌────▼───────────────┐
                   │  IN_TAKEOFF_BAG    │  Bagged for flight or same-city route (M7)
                   └────┬───────────────┘
            ┌───────────┴────────────────────────────────────────────┐
            │ delivery_type=INTERCITY          delivery_type=SAME_CITY
            ▼                                           │
 ┌──────────────────────┐                              │ (skip air leg + dest hub)
 │DISPATCHED_TO_AIRPORT │  Bag on van; left hub        │
 └────┬─────────────────┘                              │
      │                                                │
 ┌────▼──────────┐                                     │
 │  AT_AIRPORT   │  GHA acceptance scan (M8)           │
 └────┬──────────┘                                     │
      │                                                │
 ┌────▼──────────┐                                     │
 │   DEPARTED    │  Flight departed (M9)               │
 └────┬──────────┘                                     │
      │                                                │
 ┌────▼──────────┐                                     │
 │    LANDED     │  Flight arrived at dest city (M9)   │
 └────┬──────────┘                                     │
      │                                                │
 ┌────▼──────────────────┐                             │
 │  DISPATCHED_TO_HUB    │  Van from airport to hub (M6)│
 └────┬──────────────────┘                             │
      │                                                │
 ┌────▼──────────┐                                     │
 │  AT_DEST_HUB  │  Dest hub in-scan (M8)              │
 └────┬──────────┘                                     │
      │                                                │
 ┌────▼──────────────────┐                             │
 │ DEST_HUB_PROCESSING   │  Last-mile sort (M7)         │
 └────┬──────────────────┘                             │
      │                                                │
 ┌────▼───────────────────┐◄──────────────────────────┘
 │  HANDED_TO_DROP_VAN    │  Parcel loaded on drop van; hub responsibility ends
 └────┬───────────────────┘
      │
 ┌────▼──────────────┐
 │   DROP_ASSIGNED   │  Last-mile DA assigned for delivery (M5)
 └────┬──────────────┘
      │
 ┌────▼──────────────┐
 │  DROP_COLLECTED   │  DA physically collected parcel from van (M5)
 └────┬──────────────┘
    ┌─┴───────────────────────┐
    ▼                         ▼
┌─────────┐     ┌──────────────────────┐
│ DROPPED │     │   DELIVERY_FAILED    │  → reported to M11
└─────────┘     └──────────┬───────────┘
                       ┌───┴────────────────────────────┐
                       ▼                                ▼
             ┌──────────────────┐          ┌──────────────────────┐
             │  RTO_INITIATED   │          │   DROP_ASSIGNED      │ (M11: rescheduled)
             │  (owned by M11)  │          └──────────────────────┘
             └──────┬───────────┘
          ┌─────────┴───────────────────────────────────┐
          │ delivery_type=INTERCITY   delivery_type=SAME_CITY
          ▼                                    ▼
 ┌──────────────────┐              ┌──────────────────┐
 │  RTO_IN_TRANSIT  │              │  RTO_COMPLETED   │
 └──────┬───────────┘              └──────────────────┘
        │
        ▼
 ┌──────────────────┐
 │  RTO_COMPLETED   │
 └──────────────────┘
```

> **Failure routing:** Any failure state (`PICKUP_FAILED`, `DELIVERY_FAILED`) is immediately reported to M11 via Kafka. M11 owns all retry, rescheduling, and RTO logic. M4 only records the state transitions M11 instructs it to make. RTO states are driven entirely by M11.

> **SAME_CITY path:** States 8–14 (DISPATCHED_TO_AIRPORT through DEST_HUB_PROCESSING) are skipped. `IN_TAKEOFF_BAG` transitions directly to `HANDED_TO_DROP_VAN`.

---

## States Reference

| # | State | Meaning | Custody | Triggered by |
|---|---|---|---|---|
| 1 | `BOOKED` | Created; payment captured (B2C/C2C PREPAID) or COD accepted or invoiced (B2B) | Platform | M4 booking API |
| 2 | `PICKUP_ASSIGNED` | DA assigned to collect | DA | M5 `oneday.da.assigned` |
| 3 | `PICKED_UP` | DA confirmed physical pickup | DA | M5 `oneday.da.pickup_completed` |
| 4 | `HANDED_TO_PICKUP_VAN` | DA handed parcel to pickup van; DA responsibility ends | Pickup van | M5 `oneday.da.van_handoff_completed` |
| 5 | `AT_ORIGIN_HUB` | Scanned in at origin hub | Hub ops | M8 `HUB_ORIGIN_IN` scan event |
| 6 | `ORIGIN_HUB_PROCESSING` | Stand assigned; being sorted | Hub ops | M7 stand assignment event |
| 7 | `IN_TAKEOFF_BAG` | Bagged for specific flight (or same-city route) | Hub ops | M7 bag creation event |
| 8 | `DISPATCHED_TO_AIRPORT` | Bag on cron van; left the hub *(INTERCITY only)* | Cron driver | M6/M7 cron departure event |
| 9 | `AT_AIRPORT` | Handed to GHA; airline custody *(INTERCITY only)* | GHA/Airline | M8 `GHA_ACCEPTANCE` scan |
| 10 | `DEPARTED` | Flight departed *(INTERCITY only)* | Airline | M9 `flight.departed` event |
| 11 | `LANDED` | Flight arrived at destination city *(INTERCITY only)* | Airline → Dest ops | M9 `flight.landed` event |
| 12 | `DISPATCHED_TO_HUB` | Van moving from airport to destination hub *(INTERCITY only)* | Cron driver | M6/M7 van departure event |
| 13 | `AT_DEST_HUB` | Scanned in at destination hub *(INTERCITY only)* | Dest hub ops | M8 `HUB_DEST_IN` scan |
| 14 | `DEST_HUB_PROCESSING` | Last-mile sort at destination *(INTERCITY only)* | Dest hub ops | M7 dest sort event |
| 15 | `HANDED_TO_DROP_VAN` | Parcel loaded on drop van; hub responsibility ends | Drop van | M5/M6 drop van handoff event |
| 16 | `DROP_ASSIGNED` | Last-mile DA assigned for delivery | Last-mile DA | M5 `oneday.da.drop_assigned` |
| 17 | `DROP_COLLECTED` | DA physically collected parcel from van for delivery | Last-mile DA | M5 `oneday.da.drop_collected` |
| 18 | `DROPPED` | Delivery confirmed by DA | — (complete) | M5 `oneday.da.drop_completed` |
| — | `PICKUP_FAILED` | DA could not pick up; reported to M11 | — | M5 `oneday.da.pickup_failed` |
| — | `DELIVERY_FAILED` | DA could not deliver; reported to M11 | — | M5 `oneday.da.drop_failed` |
| — | `RTO_INITIATED` | Return-to-origin triggered *(owned by M11)* | Platform | M11 `oneday.m11.rto_initiated` |
| — | `RTO_IN_TRANSIT` | Return flight to origin city *(INTERCITY only; owned by M11)* | Airline | M9 return flight departed |
| — | `RTO_COMPLETED` | Returned to sender *(owned by M11)* | — (complete) | M5 return delivery confirmed |
| — | `CANCELLED` | Cancelled by customer | — (complete) | M4 cancellation API |

> States 8–14 are skipped for `SAME_CITY` shipments. `IN_TAKEOFF_BAG` transitions directly to `HANDED_TO_DROP_VAN`.

---

## Allowed Transitions

```
BOOKED
  → PICKUP_ASSIGNED             (M5: oneday.da.assigned)
  → CANCELLED                   (API: customer cancels — see BD-001 for policy)

PICKUP_ASSIGNED
  → PICKED_UP                   (M5: oneday.da.pickup_completed)
  → PICKUP_FAILED               (M5: oneday.da.pickup_failed) ── reported to M11
  → CANCELLED                   (API: customer cancels — see BD-001)

PICKED_UP
  → HANDED_TO_PICKUP_VAN        (M5: oneday.da.van_handoff_completed)
  → CANCELLED                   (API: last state allowing cancellation — see BD-001)

HANDED_TO_PICKUP_VAN
  → AT_ORIGIN_HUB               (M8: HUB_ORIGIN_IN scan)
                                  ↳ Side-effect: EtaPort.fetchEta(shipmentId, AT_ORIGIN_HUB, ctx);
                                    stores result as eta_updated; notifies customer

AT_ORIGIN_HUB
  → ORIGIN_HUB_PROCESSING       (M7: stand assignment event)

ORIGIN_HUB_PROCESSING
  → IN_TAKEOFF_BAG              (M7: bag creation event)

IN_TAKEOFF_BAG [delivery_type=INTERCITY]
  → DISPATCHED_TO_AIRPORT       (M6/M7: cron van departure event)

IN_TAKEOFF_BAG [delivery_type=SAME_CITY]
  → HANDED_TO_DROP_VAN          (M5/M6: same-city drop van handoff — skips air leg + dest hub)

DISPATCHED_TO_AIRPORT
  → AT_AIRPORT                  (M8: GHA_ACCEPTANCE scan)

AT_AIRPORT
  → DEPARTED                    (M9: flight.departed event)

DEPARTED
  → LANDED                      (M9: flight.landed event)

LANDED
  → DISPATCHED_TO_HUB           (M6/M7: van departure from airport to dest hub)

DISPATCHED_TO_HUB
  → AT_DEST_HUB                 (M8: HUB_DEST_IN scan)

AT_DEST_HUB
  → DEST_HUB_PROCESSING         (M7: dest sort event)

DEST_HUB_PROCESSING
  → HANDED_TO_DROP_VAN          (M5/M6: drop van handoff event)

HANDED_TO_DROP_VAN
  → DROP_ASSIGNED               (M5: oneday.da.drop_assigned)

DROP_ASSIGNED
  → DROP_COLLECTED              (M5: oneday.da.drop_collected)

DROP_COLLECTED
  → DROPPED                     (M5: oneday.da.drop_completed)
  → DELIVERY_FAILED             (M5: oneday.da.drop_failed) ── reported to M11

DELIVERY_FAILED
  → RTO_INITIATED               (M11: after N failed delivery attempts)
  → DROP_ASSIGNED               (M11: rescheduled delivery attempt)

PICKUP_FAILED
  → PICKUP_ASSIGNED             (M11: rescheduled pickup attempt)
  → CANCELLED                   (M11: no further pickup possible)

RTO_INITIATED [delivery_type=INTERCITY]
  → RTO_IN_TRANSIT              (M9: return flight departed)

RTO_INITIATED [delivery_type=SAME_CITY]
  → RTO_COMPLETED               (M5: return delivery to sender confirmed)

RTO_IN_TRANSIT
  → RTO_COMPLETED               (M5: return delivery to sender confirmed)
```

Any transition not listed above is rejected with `409 Conflict`.

> **M11 ownership of failure states:** M4 emits `shipment.pickup_failed` or `shipment.delivery_failed` on Kafka when entering those states. M11 is the sole consumer that decides next action (retry, reschedule, or RTO). M4 never self-initiates RTO — it only records the transition when M11 instructs via `oneday.m11.rto_initiated`.

---

## Customer-Visible State Labels

| State | Label shown to customer |
|---|---|
| `BOOKED` | Order confirmed |
| `PICKUP_ASSIGNED` | Pickup agent assigned |
| `PICKED_UP` | Parcel collected |
| `HANDED_TO_PICKUP_VAN` | Parcel handed to transport |
| `AT_ORIGIN_HUB` | Arrived at origin hub |
| `ORIGIN_HUB_PROCESSING` | Being processed at hub |
| `IN_TAKEOFF_BAG` | Sorted and bagged for dispatch |
| `DISPATCHED_TO_AIRPORT` | En route to airport |
| `AT_AIRPORT` | At airport — airline check-in |
| `DEPARTED` | In transit by air |
| `LANDED` | Arrived at destination city |
| `DISPATCHED_TO_HUB` | En route to delivery hub |
| `AT_DEST_HUB` | Arrived at destination hub |
| `DEST_HUB_PROCESSING` | Being sorted for last-mile delivery |
| `HANDED_TO_DROP_VAN` | Out for delivery |
| `DROP_ASSIGNED` | Delivery agent assigned |
| `DROP_COLLECTED` | Delivery agent en route |
| `DROPPED` | Delivered |
| `PICKUP_FAILED` | Pickup unsuccessful |
| `DELIVERY_FAILED` | Delivery unsuccessful |
| `RTO_INITIATED` | Return to sender initiated |
| `RTO_IN_TRANSIT` | Returning to sender |
| `RTO_COMPLETED` | Returned to sender |
| `CANCELLED` | Cancelled |
