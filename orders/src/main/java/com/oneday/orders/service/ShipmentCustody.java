package com.oneday.orders.service;

import com.oneday.common.domain.enums.ShipmentState;

import java.util.EnumSet;
import java.util.Set;

import static com.oneday.common.domain.enums.ShipmentState.AT_AIRPORT;
import static com.oneday.common.domain.enums.ShipmentState.AT_ORIGIN_HUB;
import static com.oneday.common.domain.enums.ShipmentState.AWAITING_SELF_DROP;
import static com.oneday.common.domain.enums.ShipmentState.BOOKED;
import static com.oneday.common.domain.enums.ShipmentState.CANCELLED;
import static com.oneday.common.domain.enums.ShipmentState.DEPARTED;
import static com.oneday.common.domain.enums.ShipmentState.DISPATCHED_TO_AIRPORT;
import static com.oneday.common.domain.enums.ShipmentState.DISPATCHED_TO_HUB;
import static com.oneday.common.domain.enums.ShipmentState.HANDED_TO_PICKUP_VAN;
import static com.oneday.common.domain.enums.ShipmentState.IN_TAKEOFF_BAG;
import static com.oneday.common.domain.enums.ShipmentState.LANDED;
import static com.oneday.common.domain.enums.ShipmentState.ORIGIN_HUB_PROCESSING;
import static com.oneday.common.domain.enums.ShipmentState.PICKED_UP;
import static com.oneday.common.domain.enums.ShipmentState.PICKUP_ASSIGNED;
import static com.oneday.common.domain.enums.ShipmentState.PICKUP_FAILED;
import static com.oneday.common.domain.enums.ShipmentState.RTO_COMPLETED;

/**
 * Custody model for a shipment's intercity journey (origin city X → destination city Y).
 *
 * <p>Custody (operational <em>authority</em> — who may act on the parcel) is single-owner and
 * follows physical handoff:</p>
 * <ul>
 *   <li><b>Origin (X)</b> holds from booking through the whole air leg, <em>retaining until the
 *       destination hub's receipt scan</em> ({@link ShipmentState#AT_DEST_HUB}). This deliberately
 *       leaves no unowned in-flight window. {@code PICKUP_FAILED} and {@code CANCELLED} are
 *       origin-side; {@code RTO_COMPLETED} is the parcel back with origin.</li>
 *   <li><b>Destination (Y)</b> holds from {@code AT_DEST_HUB} onward — dest-hub sortation,
 *       delivery, hub-collect, {@code DELIVERY_FAILED}, and RTO initiation/return (Y owns the
 *       return until it lands back at origin as {@code RTO_COMPLETED}).</li>
 * </ul>
 *
 * <p>For SAME_CITY shipments X == Y, so the resolved city is identical either way.</p>
 *
 * <p>Note: this is the <em>authority</em> primitive. Read <em>visibility</em> is broader — both the
 * origin and destination city see a shipment end-to-end — and is enforced at the query layer, not
 * here. Act-endpoints (reassign DA, approve override, resolve exception) live in M5/M7/M11 and will
 * gate on {@link #custodian(ShipmentState)} once they exist.</p>
 */
public final class ShipmentCustody {

    public enum Custodian { ORIGIN, DESTINATION }

    // Exhaustive set of states where the ORIGIN city holds custody. Every other state
    // (dest-hub onward, delivery, hub-collect, delivery/RTO exceptions) is DESTINATION-held.
    private static final Set<ShipmentState> ORIGIN_HELD = EnumSet.of(
            BOOKED, PICKUP_ASSIGNED, PICKED_UP, HANDED_TO_PICKUP_VAN, AWAITING_SELF_DROP,
            AT_ORIGIN_HUB, ORIGIN_HUB_PROCESSING, IN_TAKEOFF_BAG,
            DISPATCHED_TO_AIRPORT, AT_AIRPORT, DEPARTED, LANDED, DISPATCHED_TO_HUB,
            PICKUP_FAILED, CANCELLED, RTO_COMPLETED);

    private ShipmentCustody() {}

    /** The city currently holding operational authority for a shipment in {@code state}. */
    public static Custodian custodian(ShipmentState state) {
        return ORIGIN_HELD.contains(state) ? Custodian.ORIGIN : Custodian.DESTINATION;
    }
}
