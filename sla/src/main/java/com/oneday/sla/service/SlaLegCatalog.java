package com.oneday.sla.service;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.sla.config.SlaProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.oneday.common.domain.enums.ShipmentState.*;
import static com.oneday.common.domain.enums.SlaLegType.*;

/**
 * The leg model: which legs a shipment traverses (by {@link DeliveryType}), each leg's budget
 * (config, Annexure G), and the mapping from a live {@link ShipmentState} to the leg it belongs to.
 * Pure lookups — the engine drives the timestamps.
 */
@Component
public class SlaLegCatalog {

    private static final List<SlaLegType> INTERCITY_PLAN =
            List.of(FIRST_MILE, ORIGIN_HUB, ORIGIN_AIRPORT, AIR, DEST_AIRPORT, DEST_HUB, LAST_MILE);

    // Same-city collapses the air legs and the second hub (M10 design Part II).
    private static final List<SlaLegType> SAME_CITY_PLAN =
            List.of(FIRST_MILE, ORIGIN_HUB, LAST_MILE);

    // Which leg is "live" while a shipment sits in a given state. Terminal / exception states map to none.
    private static final Map<ShipmentState, SlaLegType> STATE_TO_LEG = new EnumMap<>(ShipmentState.class);

    static {
        put(FIRST_MILE, BOOKED, PICKUP_ASSIGNED, PICKED_UP, HANDED_TO_PICKUP_VAN, RETURNED_TO_HUB, AWAITING_SELF_DROP);
        put(ORIGIN_HUB, AT_ORIGIN_HUB, ORIGIN_HUB_PROCESSING, IN_TAKEOFF_BAG);
        put(ORIGIN_AIRPORT, DISPATCHED_TO_AIRPORT, AT_AIRPORT);
        put(AIR, DEPARTED);
        put(DEST_AIRPORT, LANDED, DISPATCHED_TO_HUB);
        put(DEST_HUB, AT_DEST_HUB, DEST_HUB_PROCESSING, AWAITING_HUB_COLLECT);
        put(LAST_MILE, HANDED_TO_DROP_VAN, DROP_ASSIGNED, DROP_COLLECTED, HUB_DELIVERY_ASSIGNED, COLLECTED_FROM_HUB);
    }

    private static void put(SlaLegType leg, ShipmentState... states) {
        for (ShipmentState s : states) {
            STATE_TO_LEG.put(s, leg);
        }
    }

    private final SlaProperties props;

    public SlaLegCatalog(SlaProperties props) {
        this.props = props;
    }

    public List<SlaLegType> plan(DeliveryType deliveryType) {
        return deliveryType == DeliveryType.SAME_CITY ? SAME_CITY_PLAN : INTERCITY_PLAN;
    }

    public int budgetMinutes(SlaLegType leg) {
        return props.getLegs().getOrDefault(leg, 0);
    }

    /** The leg a shipment is on while in {@code state}, if any (empty for terminal/exception states). */
    public Optional<SlaLegType> activeLeg(ShipmentState state) {
        return Optional.ofNullable(STATE_TO_LEG.get(state));
    }

    /** Successful delivery — the SLA closes GREEN (or BREACHED if delivered past the internal target). */
    public boolean isTerminalSuccess(ShipmentState state) {
        return state == DROPPED || state == HUB_COLLECTED;
    }

    /** A hard failure while still in flight — the SLA is breached but stays open until RTO/cancel. */
    public boolean isException(ShipmentState state) {
        return state == PICKUP_FAILED || state == DELIVERY_FAILED
                || state == RTO_INITIATED || state == RTO_IN_TRANSIT;
    }
}
