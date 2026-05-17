package com.oneday.common.domain.enums;

public enum ShipmentState {

    // ── Normal flow ────────────────────────────────────────────────────────
    BOOKED,
    PICKUP_ASSIGNED,
    PICKED_UP,
    HANDED_TO_PICKUP_VAN,
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

    HANDED_TO_DROP_VAN,
    DROP_ASSIGNED,
    DROP_COLLECTED,
    DROPPED,

    // ── Exception / failure states (owned by M11) ──────────────────────────
    PICKUP_FAILED,
    DELIVERY_FAILED,
    RTO_INITIATED,
    RTO_IN_TRANSIT,  // INTERCITY only
    RTO_COMPLETED,
    CANCELLED
}
