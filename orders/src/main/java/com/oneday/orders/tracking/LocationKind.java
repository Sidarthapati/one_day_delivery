package com.oneday.orders.tracking;

/**
 * What kind of place a shipment is at right now — the shape the tracking UI draws. Derived from the
 * M4 {@link com.oneday.common.domain.enums.ShipmentState} by {@link LocationResolver}.
 */
public enum LocationKind {

    /** Resting with the sender before pickup (or awaiting self-drop). Static origin pin. */
    WITH_CUSTOMER,

    /** Sitting at a hub (origin or destination). Static hub pin. */
    STATIONARY_HUB,

    /** Waiting at an airport. Static airport pin. */
    STATIONARY_AIRPORT,

    /** On the move with a delivery associate. Live dot when GPS is fresh, else static fallback. */
    MOVING_DA,

    /** On the move on a van between hub and DA. Live dot when GPS is fresh, else static fallback. */
    MOVING_VAN,

    /** In the air. No live point in v1 — the UI shows the route arc + "In transit by air". */
    ON_FLIGHT,

    /** Delivered (or collected from hub). Static destination pin. */
    DELIVERED
}
