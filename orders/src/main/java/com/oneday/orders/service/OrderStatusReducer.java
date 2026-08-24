package com.oneday.orders.service;

import com.oneday.common.domain.enums.ShipmentState;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Reduces the states of an order's child shipments into a single order-level status. An order is a
 * booking grouping whose parcels move independently, so the rollup must express divergence
 * ("partially delivered", "mixed") rather than pretend the parcels share one state.
 *
 * <p>Defined once here and reused by every console so the business and customer views agree.</p>
 */
public final class OrderStatusReducer {

    private OrderStatusReducer() {}

    /** Terminal success — the parcel reached the customer. */
    private static final Set<ShipmentState> DELIVERED =
            EnumSet.of(ShipmentState.DROPPED, ShipmentState.HUB_COLLECTED);

    /** Terminal returned-to-sender. */
    private static final Set<ShipmentState> RETURNED =
            EnumSet.of(ShipmentState.RTO_COMPLETED);

    public enum OrderStatus {
        EMPTY,                  // no shipments (e.g. a checkout where every item failed)
        BOOKED,                 // all parcels still at booking
        IN_PROGRESS,            // parcels moving through the chain
        PARTIALLY_DELIVERED,    // some delivered, some still active
        DELIVERED,              // every parcel delivered
        RETURNED,               // every parcel RTO-completed
        CANCELLED,              // every parcel cancelled
        MIXED                   // terminal divergence (e.g. some delivered, some cancelled/returned)
    }

    /** @return the single order-level status for the given child shipment states. */
    public static OrderStatus reduce(Collection<ShipmentState> childStates) {
        if (childStates == null || childStates.isEmpty()) {
            return OrderStatus.EMPTY;
        }

        int total = childStates.size();
        int delivered = 0, returned = 0, cancelled = 0, booked = 0, terminal = 0;
        for (ShipmentState s : childStates) {
            if (DELIVERED.contains(s)) { delivered++; terminal++; }
            else if (RETURNED.contains(s)) { returned++; terminal++; }
            else if (s == ShipmentState.CANCELLED) { cancelled++; terminal++; }
            else if (s == ShipmentState.BOOKED) { booked++; }
        }

        if (delivered == total) return OrderStatus.DELIVERED;
        if (returned == total)  return OrderStatus.RETURNED;
        if (cancelled == total) return OrderStatus.CANCELLED;
        if (booked == total)    return OrderStatus.BOOKED;

        // Some parcels delivered but not all → partial delivery (the headline "mixed" ops care about).
        if (delivered > 0 && terminal < total) return OrderStatus.PARTIALLY_DELIVERED;

        // More than one distinct terminal outcome, or all terminal but not uniform → mixed.
        if (terminal == total) return OrderStatus.MIXED;

        // Otherwise parcels are still moving through the chain.
        return OrderStatus.IN_PROGRESS;
    }
}
