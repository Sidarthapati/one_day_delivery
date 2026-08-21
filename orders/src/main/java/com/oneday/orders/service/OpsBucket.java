package com.oneday.orders.service;

import com.oneday.common.domain.enums.ShipmentState;

/**
 * Ops-console grouping of the 30 {@link ShipmentState}s into the handful of buckets the
 * operations team monitors day to day. Deliberately distinct from
 * {@link CustomerVisibleStateMapper} (which is tuned for customers) — ops care about
 * hub processing and per-leg detail that customers never see.
 *
 * <p>The mapping is an <b>exhaustive switch with no {@code default}</b>: adding a new
 * {@code ShipmentState} fails compilation here until it is placed in a bucket, so a new
 * state can never silently fall out of every count.
 */
public enum OpsBucket {

    /** Booked, awaiting or in first-mile pickup (DA pickup or self-drop, up to the origin hub). */
    BOOKED_PICKUP,

    /** Between hubs — origin-hub processing, flight, and destination-hub processing. */
    IN_TRANSIT,

    /** Last mile — assigned/out for delivery, or ready for hub collect. */
    OUT_FOR_DELIVERY,

    /** Terminal success — delivered to door or collected from hub. */
    DELIVERED,

    /** Failed pickup/delivery, RTO in any phase, or cancelled — the queue that needs attention. */
    EXCEPTIONS;

    public static OpsBucket of(ShipmentState state) {
        return switch (state) {
            case BOOKED, PICKUP_ASSIGNED, PICKED_UP, HANDED_TO_PICKUP_VAN,
                 AWAITING_SELF_DROP, RETURNED_TO_HUB -> BOOKED_PICKUP;
            case AT_ORIGIN_HUB, ORIGIN_HUB_PROCESSING, IN_TAKEOFF_BAG, DISPATCHED_TO_AIRPORT,
                 AT_AIRPORT, DEPARTED, LANDED, DISPATCHED_TO_HUB, AT_DEST_HUB,
                 DEST_HUB_PROCESSING -> IN_TRANSIT;
            case HANDED_TO_DROP_VAN, DROP_ASSIGNED, DROP_COLLECTED, HUB_DELIVERY_ASSIGNED,
                 COLLECTED_FROM_HUB, AWAITING_HUB_COLLECT -> OUT_FOR_DELIVERY;
            case DROPPED, HUB_COLLECTED -> DELIVERED;
            case PICKUP_FAILED, DELIVERY_FAILED, RTO_INITIATED, RTO_IN_TRANSIT,
                 RTO_COMPLETED, CANCELLED -> EXCEPTIONS;
        };
    }
}
