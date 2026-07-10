package com.oneday.hub.domain;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.hub.service.exception.UndeterminedArrivalException;

/**
 * How a parcel reached the dock (§6). This is NOT read off the barcode and NOT an operator input —
 * it is <b>derived</b> from the parcel's current M4 state, i.e. the leg it just finished (M7-D-005).
 * The three prior states are mutually exclusive, so the derivation is unambiguous.
 */
public enum ArrivalMode {
    VAN,        // first-mile van unload (came in on a consolidation van)
    SELF_DROP,  // customer brought it to the hub
    AIRPORT;    // landed bag, shuttle back (destination side; PR #2)

    /** Derive the arrival mode from the state the parcel is in when it hits the dock. */
    public static ArrivalMode fromState(ShipmentState state) {
        return switch (state) {
            case HANDED_TO_PICKUP_VAN, AT_ORIGIN_HUB -> VAN;   // M6 may have already in-scanned it
            case AWAITING_SELF_DROP -> SELF_DROP;
            case LANDED, DISPATCHED_TO_HUB, AT_DEST_HUB -> AIRPORT;
            default -> throw new UndeterminedArrivalException(state);
        };
    }
}
