package com.oneday.orders.service;

import com.oneday.common.domain.enums.ShipmentState;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps internal {@link ShipmentState} values to the customer-visible labels
 * shown in the tracking API and notification messages.
 *
 * <p>Labels are sourced from the M4 design doc (§5.4). Changing a label here
 * automatically propagates to every place that calls {@link #labelFor(ShipmentState)}
 * — there is no other place in the codebase where these strings should be duplicated.</p>
 */
@Component
public class CustomerVisibleStateMapper {

    private static final Map<ShipmentState, String> LABELS = new EnumMap<>(ShipmentState.class);

    static {
        LABELS.put(ShipmentState.BOOKED,                  "Order confirmed");
        LABELS.put(ShipmentState.PICKUP_ASSIGNED,          "Pickup agent assigned");
        LABELS.put(ShipmentState.PICKED_UP,                "Parcel collected");
        LABELS.put(ShipmentState.HANDED_TO_PICKUP_VAN,     "Parcel handed to transport");
        LABELS.put(ShipmentState.AWAITING_SELF_DROP,       "Please bring your parcel to the origin hub");
        LABELS.put(ShipmentState.AT_ORIGIN_HUB,            "Arrived at origin hub");
        LABELS.put(ShipmentState.ORIGIN_HUB_PROCESSING,    "Being processed at hub");
        LABELS.put(ShipmentState.IN_TAKEOFF_BAG,           "Sorted and bagged for dispatch");
        LABELS.put(ShipmentState.DISPATCHED_TO_AIRPORT,    "En route to airport");
        LABELS.put(ShipmentState.AT_AIRPORT,               "At airport — airline check-in");
        LABELS.put(ShipmentState.DEPARTED,                 "In transit by air");
        LABELS.put(ShipmentState.LANDED,                   "Arrived at destination city");
        LABELS.put(ShipmentState.DISPATCHED_TO_HUB,        "En route to delivery hub");
        LABELS.put(ShipmentState.AT_DEST_HUB,              "Arrived at destination hub");
        LABELS.put(ShipmentState.DEST_HUB_PROCESSING,      "Being sorted for last-mile delivery");
        LABELS.put(ShipmentState.HANDED_TO_DROP_VAN,       "Out for delivery");
        LABELS.put(ShipmentState.DROP_ASSIGNED,            "Delivery agent assigned");
        LABELS.put(ShipmentState.DROP_COLLECTED,           "Delivery agent en route");
        LABELS.put(ShipmentState.DROPPED,                  "Delivered");
        LABELS.put(ShipmentState.AWAITING_HUB_COLLECT,     "Your parcel is ready — collect from the hub");
        LABELS.put(ShipmentState.HUB_COLLECTED,            "Collected from hub");
        LABELS.put(ShipmentState.PICKUP_FAILED,            "Pickup unsuccessful");
        LABELS.put(ShipmentState.DELIVERY_FAILED,          "Delivery unsuccessful");
        LABELS.put(ShipmentState.RTO_INITIATED,            "Return to sender initiated");
        LABELS.put(ShipmentState.RTO_IN_TRANSIT,           "Returning to sender");
        LABELS.put(ShipmentState.RTO_COMPLETED,            "Returned to sender");
        LABELS.put(ShipmentState.CANCELLED,                "Cancelled");
    }

    /**
     * Returns the customer-visible label for {@code state}.
     *
     * @throws IllegalArgumentException if {@code state} has no registered label —
     *         indicates a new state was added to {@link ShipmentState} without
     *         updating this mapper
     */
    public String labelFor(ShipmentState state) {
        String label = LABELS.get(state);
        if (label == null) {
            throw new IllegalArgumentException(
                    "No customer-visible label registered for state: " + state +
                    ". Add an entry to CustomerVisibleStateMapper.LABELS.");
        }
        return label;
    }
}
