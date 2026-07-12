package com.oneday.common.domain.enums;

public enum ShipmentState {

    // ── Normal flow ────────────────────────────────────────────────────────
    BOOKED,

    // DA_PICKUP path
    PICKUP_ASSIGNED,
    PICKED_UP,
    HANDED_TO_PICKUP_VAN,

    // HUB_RETURN first-mile path — replaces HANDED_TO_PICKUP_VAN in cities with no van (M6 gate off).
    // There is no pickup van: the DA carries the collected parcel back to the origin hub on their
    // scheduled return and hands it off there.
    RETURNED_TO_HUB,

    // SELF_DROP path — skips the three states above
    AWAITING_SELF_DROP,

    AT_ORIGIN_HUB,
    ORIGIN_HUB_PROCESSING,
    IN_TAKEOFF_BAG,

    // INTERCITY only — skipped for SAME_CITY; IN_TAKEOFF_BAG → HANDED_TO_DROP_VAN directly
    DISPATCHED_TO_AIRPORT,
    AT_AIRPORT,
    DEPARTED,
    LANDED,
    DISPATCHED_TO_HUB,
    AT_DEST_HUB,
    DEST_HUB_PROCESSING,

    // DA_DELIVERY path
    HANDED_TO_DROP_VAN,
    DROP_ASSIGNED,
    DROP_COLLECTED,
    DROPPED,

    // HUB_RETURN last-mile path — replaces HANDED_TO_DROP_VAN/DROP_ASSIGNED/DROP_COLLECTED in cities
    // with no van (M6 gate off). There is no drop van: a territory DA is assigned the delivery, collects
    // the parcel at the destination hub on their next visit, then delivers it (→ DROPPED).
    HUB_DELIVERY_ASSIGNED,  // territory DA assigned; parcel waiting at dest hub for the DA's next visit
    COLLECTED_FROM_HUB,     // DA collected the parcel from the hub — out for delivery

    // HUB_COLLECT path — skips the four states above
    AWAITING_HUB_COLLECT,  // parcel ready at dest hub; customer yet to arrive
    HUB_COLLECTED,         // customer collected from hub (terminal success)

    // ── Exception / failure states (owned by M11) ──────────────────────────
    PICKUP_FAILED,
    DELIVERY_FAILED,
    RTO_INITIATED,
    RTO_IN_TRANSIT,  // INTERCITY only
    RTO_COMPLETED,
    CANCELLED
}
