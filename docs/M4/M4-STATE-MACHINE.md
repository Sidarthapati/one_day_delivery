# M4 — Shipment State Machine

> Extracted from [M4-ORDERS-DESIGN.md](M4-ORDERS-DESIGN.md) §6.  
> Update this file whenever the state machine changes. Ops sign-off required before implementation.

---

## Visual Flow

```
━━━ PICKUP LEG ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                              ┌──────────────────────┐
                              │        BOOKED        │
                              └──────────┬───────────┘
                   ┌────────────────────┤
       pickup_type=DA_PICKUP    pickup_type=SELF_DROP
                   │                    │
                   ▼                    ▼
     ┌─────────────────────┐  ┌──────────────────────────┐
     │   PICKUP_ASSIGNED   │  │    AWAITING_SELF_DROP    │
     │ [side-effect: OTP   │  └─────────────┬────────────┘
     │  sent to customer]  │        [QR: SELF_DROP_ACCEPTED]
     └──────────┬──────────┘                │
         ┌──────┴──────────┐                │
  [OTP]  ▼                 ▼                │
┌──────────────┐    ┌───────────┐           │
│PICKUP_FAILED │    │ PICKED_UP │           │
│  (→ M11)     │    └─────┬─────┘           │
└──────────────┘          │                 │
                  [QR: VAN_HANDOFF_COMPLETED]│
                          ▼                 │
              ┌───────────────────────────┐ │
              │    HANDED_TO_PICKUP_VAN   │ │
              └─────────────┬─────────────┘ │
                    [QR: HUB_ORIGIN_IN]      │
                            └───────────┬───┘
                                        ▼
                              ┌──────────────────┐
                              │  AT_ORIGIN_HUB   │  EtaPort.fetchEta called
                              └────────┬─────────┘

━━━ ORIGIN HUB ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                              ┌──────────────────┐
                              │  AT_ORIGIN_HUB   │
                              └────────┬─────────┘
                                [QR: STAND_ASSIGNED]
                                       ▼
                          ┌────────────────────────────┐
                          │   ORIGIN_HUB_PROCESSING    │
                          └──────────────┬─────────────┘
                                  [QR: BAG_CREATED]
                                         ▼
                                ┌──────────────────┐
                                │  IN_TAKEOFF_BAG  │
                                └────────┬─────────┘
                     ┌──────────────────┤
              INTERCITY          SAME_CITY
                     │                  │
          [QR: DEPARTED_HUB]  [QR: SAMECITY_OUTBOUND]
                     ▼                  │
     ┌───────────────────────────┐      │
     │   DISPATCHED_TO_AIRPORT   │      │
     └──────────────┬────────────┘      │
            [QR: GHA_ACCEPTANCE]        │
                     ▼                  │
             ┌──────────────┐           │
             │  AT_AIRPORT  │           │
             └──────┬───────┘           │
           [System: DEPARTED]           │
                     ▼                  │
             ┌──────────────┐           │
             │   DEPARTED   │           │
             └──────┬───────┘           │
            [System: LANDED]            │
                     ▼                  │
             ┌──────────────┐           │
             │    LANDED    │           │
             └──────┬───────┘           │
         [QR: DEPARTED_AIRPORT]         │
                     ▼                  │
       ┌─────────────────────────┐      │
       │    DISPATCHED_TO_HUB   │      │
       └──────────────┬──────────┘      │
               [QR: HUB_DEST_IN]        │
                     ▼                  │
             ┌──────────────┐           │
             │ AT_DEST_HUB  │           │
             └──────┬───────┘           │
         [QR: DEST_SORT_COMPLETE]       │
                     ▼                  │
          ┌──────────────────────┐      │
          │  DEST_HUB_PROCESSING │      │
          └──────────┬───────────┘      │
          ┌──────────┤                  │
     DA_DELIVERY  HUB_COLLECT           │
          │            │                │
 [QR: DROP_VAN_HANDOFF][QR: OD-9 TBD]  │
          │            ▼                │
          │ ┌──────────────────────────┐│
          │ │   AWAITING_HUB_COLLECT  ││
          │ └───────────┬─────────────┘│
          │   [QR: HUB_COLLECT_COMPLETED]
          │             ▼              │
          │ ┌──────────────────────┐   │
          │ │    HUB_COLLECTED     │ ✓ │
          │ └──────────────────────┘   │
          ▼                            │
┌────────────────────────┐◄────────────┘
│   HANDED_TO_DROP_VAN   │
└──────────┬─────────────┘

━━━ DELIVERY LEG ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                        ┌────────────────────────┐
                        │   HANDED_TO_DROP_VAN   │
                        └──────────┬─────────────┘
                           [System: DROP_ASSIGNED]
                                   ▼
                          ┌──────────────────┐
                          │   DROP_ASSIGNED  │
                          └────────┬─────────┘
                          [QR: DROP_COLLECTED]
                                   ▼
                          ┌──────────────────┐
                          │  DROP_COLLECTED  │
                          └────────┬─────────┘
                         ┌─────────┴──────────────────┐
                  [OD-8] ▼                             ▼
                     ┌─────────┐          ┌──────────────────────┐
                     │ DROPPED │ ✓        │   DELIVERY_FAILED    │ → M11
                     └─────────┘          └──────────┬───────────┘
                                              ┌───────┴───────────────────────┐
                                              ▼                               ▼
                                   ┌───────────────────┐      ┌──────────────────────────┐
                                   │   RTO_INITIATED   │      │  DROP_ASSIGNED (retry)   │
                                   │   (M11 owned)     │      └──────────────────────────┘
                                   └──────┬────────────┘
                                ┌─────────┴──────────────────┐
                             INTERCITY                    SAME_CITY
                                ▼                             ▼
                       ┌──────────────────┐       ┌──────────────────┐
                       │  RTO_IN_TRANSIT  │       │  RTO_COMPLETED   │ ✓
                       └──────┬───────────┘       └──────────────────┘
                              ▼
                       ┌──────────────────┐
                       │  RTO_COMPLETED   │ ✓
                       └──────────────────┘

━━━ CANCELLATION & FAILURE ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  CANCELLED ✓ (terminal) — via API from:
    BOOKED, PICKUP_ASSIGNED, PICKED_UP       (DA_PICKUP — cutoff after PICKED_UP)
    BOOKED, AWAITING_SELF_DROP              (SELF_DROP — cutoff after AWAITING_SELF_DROP)

  PICKUP_FAILED → PICKUP_ASSIGNED (M11: retry) or CANCELLED (M11: abandon)

━━━ VERIFICATION LEGEND ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  [OTP]           Customer receives 4-digit code via SMS; DA enters in DA app
  [QR: <event>]   Parcel label QR scanned; scan fires the named event
  [System: <evt>] Automated event (airline data feed, system-to-system); no physical scan
  [OD-8]          Open decision — OTP or QR at delivery (mirrors pickup recommended)
  [OD-9 TBD]      Open decision — event for DEST_HUB_PROCESSING → AWAITING_HUB_COLLECT
```

> **Failure routing:** Any failure state (`PICKUP_FAILED`, `DELIVERY_FAILED`) is immediately reported to M11 via Kafka. M11 owns all retry, rescheduling, and RTO logic. M4 only records the state transitions M11 instructs it to make. RTO states are driven entirely by M11.

> **SAME_CITY path:** States `DISPATCHED_TO_AIRPORT` through `DEST_HUB_PROCESSING` are skipped. `IN_TAKEOFF_BAG` transitions directly to `HANDED_TO_DROP_VAN` via `SAMECITY_OUTBOUND`.

---

## States Reference

| # | State | Meaning | Custody | Triggered by | Verification to enter |
|---|---|---|---|---|---|
| 1 | `BOOKED` | Created; payment captured (B2C/C2C PREPAID) or COD accepted or invoiced (B2B) | Platform | M4 booking API | Razorpay signature (PREPAID); none (COD/B2B) |
| 2 | `PICKUP_ASSIGNED` | DA assigned to collect | DA | M5 `oneday.da.assigned` | System event — no physical handover. Side-effect: M4 generates pickup OTP and sends to customer via SMS |
| 3 | `PICKED_UP` | DA confirmed physical pickup after OTP verification | DA | M4 OTP verify endpoint (HTTP, called by M5 DA app) | **OTP** — 4-digit code sent to customer's phone; DA enters in DA app |
| 4 | `HANDED_TO_PICKUP_VAN` | DA handed parcel to pickup van; DA responsibility ends | Pickup van | M5 `oneday.da.van_handoff_completed` | **QR scan** — DA scans parcel label QR in DA app at handover |
| 5 | `AT_ORIGIN_HUB` | Scanned in at origin hub | Hub ops | M8 `HUB_ORIGIN_IN` / `SELF_DROP_ACCEPTED` scan | **QR scan** — hub scan station reads parcel label |
| 6 | `ORIGIN_HUB_PROCESSING` | Stand assigned; being sorted | Hub ops | M7 stand assignment event | **QR scan** — hub system scan at stand assignment |
| 7 | `IN_TAKEOFF_BAG` | Bagged for specific flight (or same-city route) | Hub ops | M7 bag creation event | **QR scan** — bag seal scan by hub ops |
| 8 | `DISPATCHED_TO_AIRPORT` | Bag loaded on cron; left the hub *(INTERCITY only)* | Cron driver | M6/M7 `DEPARTED_HUB` cron event | **QR scan** — cron driver scans bag barcode at hub loading bay |
| 9 | `AT_AIRPORT` | Handed to GHA; airline custody *(INTERCITY only)* | GHA/Airline | M8 `GHA_ACCEPTANCE` scan | **QR scan** — GHA acceptance scan terminal |
| 10 | `DEPARTED` | Flight departed *(INTERCITY only)* | Airline | M9 `flight.departed` event | System event — airline data feed; no physical scan |
| 11 | `LANDED` | Flight arrived at destination city *(INTERCITY only)* | Airline → Dest ops | M9 `flight.landed` event | System event — airline data feed; no physical scan |
| 12 | `DISPATCHED_TO_HUB` | Cron moving from airport to destination hub *(INTERCITY only)* | Cron driver | M6 `DEPARTED_AIRPORT` cron event | **QR scan** — cron driver scans bags at airport loading before departure |
| 13 | `AT_DEST_HUB` | Scanned in at destination hub *(INTERCITY only)* | Dest hub ops | M8 `HUB_DEST_IN` scan | **QR scan** — hub scan station reads parcel label on arrival |
| 14 | `DEST_HUB_PROCESSING` | Last-mile sort at destination *(INTERCITY only)* | Dest hub ops | M7 `DEST_SORT_COMPLETE` event | **QR scan** — hub system scan at sort completion |
| 15 | `HANDED_TO_DROP_VAN` | Parcel loaded on drop van; hub responsibility ends | Drop van | M7 `DROP_VAN_HANDOFF` event | **QR scan** — hub scan station at van loading bay |
| 16 | `DROP_ASSIGNED` | Last-mile DA assigned for delivery | Last-mile DA | M5 `oneday.da.drop_assigned` | System event — no physical handover yet |
| 17 | `DROP_COLLECTED` | DA physically collected parcel from van | Last-mile DA | M5 `oneday.da.drop_collected` | **QR scan** — DA scans parcel label QR in DA app when collecting from van |
| 18 | `DROPPED` | Delivery confirmed by DA | — (complete) | M5 `oneday.da.drop_completed` | **See OD-8** — OTP (mirrors pickup) or QR on DA app delivery screen |
| — | `AWAITING_SELF_DROP` | Self-drop booked; sender yet to arrive at origin hub | Platform | M4 booking API (immediate on SELF_DROP booking) | None — system state; no physical handover |
| — | `AWAITING_HUB_COLLECT` | Parcel staged at dest hub; receiver yet to collect | Dest hub ops | M7 (see OD-9 — event TBD) | **QR scan** — hub ops scan when staging parcel for collection |
| — | `HUB_COLLECTED` | Receiver collected parcel from destination hub | — (complete) | M8 `HUB_COLLECT_COMPLETED` scan | **QR scan** — hub staff scan at collection counter |
| — | `PICKUP_FAILED` | DA could not pick up; reported to M11 | — | M5 `oneday.da.pickup_failed` | System event |
| — | `DELIVERY_FAILED` | DA could not deliver; reported to M11 | — | M5 `oneday.da.drop_failed` | System event |
| — | `RTO_INITIATED` | Return-to-origin triggered *(owned by M11)* | Platform | M11 `oneday.m11.rto_initiated` | System event |
| — | `RTO_IN_TRANSIT` | Return flight to origin city *(INTERCITY only; owned by M11)* | Airline | M9 return flight departed | System event — airline data feed |
| — | `RTO_COMPLETED` | Returned to sender *(owned by M11)* | — (complete) | M5 return delivery confirmed | System event |
| — | `CANCELLED` | Cancelled by customer | — (complete) | M4 cancellation API | JWT-authenticated API call |

> States 8–14 are skipped for `SAME_CITY` shipments. `IN_TAKEOFF_BAG` transitions directly to `HANDED_TO_DROP_VAN`.

---

## Allowed Transitions

```
BOOKED [pickup_type=DA_PICKUP]
  → PICKUP_ASSIGNED             (M5: oneday.da.assigned — system event; no physical handover)
  → CANCELLED                   (API: customer cancels — see BD-001 for policy)

BOOKED [pickup_type=SELF_DROP]
  → AWAITING_SELF_DROP          (M4: immediate on booking — no DA assigned)
  → CANCELLED                   (API: customer cancels)

AWAITING_SELF_DROP
  → AT_ORIGIN_HUB               (M8: SELF_DROP_ACCEPTED scan) [QR SCAN — hub staff scan at drop-off counter]
  → CANCELLED                   (API: customer cancels before arriving)

PICKUP_ASSIGNED
  → PICKED_UP                   (M4: OTP verify endpoint — DA calls after customer provides OTP) [OTP]
                                  ↳ Side-effect on entering PICKUP_ASSIGNED: M4 generates 4-digit OTP,
                                    stores with 10-min TTL, sends to customer phone via NotificationPort
  → PICKUP_FAILED               (M5: oneday.da.pickup_failed) ── reported to M11
  → CANCELLED                   (API: customer cancels — see BD-001)

PICKED_UP
  → HANDED_TO_PICKUP_VAN        (M5: oneday.da.van_handoff_completed) [QR SCAN — DA scans parcel in DA app]
  → CANCELLED                   (API: last state allowing cancellation for DA_PICKUP — see BD-001)

HANDED_TO_PICKUP_VAN
  → AT_ORIGIN_HUB               (M8: HUB_ORIGIN_IN scan) [QR SCAN — hub scan station]
                                  ↳ Side-effect: EtaPort.fetchEta(shipmentId, AT_ORIGIN_HUB, ctx);
                                    stores result as eta_updated; notifies customer

AT_ORIGIN_HUB
  → ORIGIN_HUB_PROCESSING       (M7: STAND_ASSIGNED) [QR SCAN — hub system scan at stand assignment]

ORIGIN_HUB_PROCESSING
  → IN_TAKEOFF_BAG              (M7: BAG_CREATED) [QR SCAN — bag seal scan by hub ops]

IN_TAKEOFF_BAG [delivery_type=INTERCITY]
  → DISPATCHED_TO_AIRPORT       (M6/M7: Cron DEPARTED_HUB) [QR SCAN — cron driver scans bag barcode at hub loading bay]

IN_TAKEOFF_BAG [delivery_type=SAME_CITY]
  → HANDED_TO_DROP_VAN          (M7: SAMECITY_OUTBOUND) [QR SCAN — hub scan station at van loading]

DISPATCHED_TO_AIRPORT
  → AT_AIRPORT                  (M8: GHA_ACCEPTANCE scan) [QR SCAN — GHA acceptance scan terminal]

AT_AIRPORT
  → DEPARTED                    (M9: flight.departed event) [System event — airline data feed; no physical scan]

DEPARTED
  → LANDED                      (M9: flight.landed event) [System event — airline data feed; no physical scan]

LANDED
  → DISPATCHED_TO_HUB           (M6: Cron DEPARTED_AIRPORT) [QR SCAN — cron driver scans bags at airport before loading]

DISPATCHED_TO_HUB
  → AT_DEST_HUB                 (M8: HUB_DEST_IN scan) [QR SCAN — hub scan station on arrival]

AT_DEST_HUB
  → DEST_HUB_PROCESSING         (M7: DEST_SORT_COMPLETE) [QR SCAN — hub system scan at sort completion]

DEST_HUB_PROCESSING [drop_type=DA_DELIVERY]
  → HANDED_TO_DROP_VAN          (M7: DROP_VAN_HANDOFF) [QR SCAN — hub scan station at van loading bay]

DEST_HUB_PROCESSING [drop_type=HUB_COLLECT]
  → AWAITING_HUB_COLLECT        (M7: see OD-9 — event TBD) [QR SCAN — hub ops scan when staging parcel]

AWAITING_HUB_COLLECT
  → HUB_COLLECTED               (M8: HUB_COLLECT_COMPLETED scan) [QR SCAN — hub staff scan at collection counter]

HANDED_TO_DROP_VAN
  → DROP_ASSIGNED               (M5: oneday.da.drop_assigned — system event; no physical handover yet)

DROP_ASSIGNED
  → DROP_COLLECTED              (M5: oneday.da.drop_collected) [QR SCAN — DA scans parcel in DA app when collecting from van]

DROP_COLLECTED
  → DROPPED                     (M5: oneday.da.drop_completed) [See OD-8 — OTP or QR at delivery]
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
| `AWAITING_SELF_DROP` | Please bring your parcel to the origin hub |
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
| `AWAITING_HUB_COLLECT` | Your parcel is ready — collect from the hub |
| `HUB_COLLECTED` | Collected from hub |
| `PICKUP_FAILED` | Pickup unsuccessful |
| `DELIVERY_FAILED` | Delivery unsuccessful |
| `RTO_INITIATED` | Return to sender initiated |
| `RTO_IN_TRANSIT` | Returning to sender |
| `RTO_COMPLETED` | Returned to sender |
| `CANCELLED` | Cancelled |
