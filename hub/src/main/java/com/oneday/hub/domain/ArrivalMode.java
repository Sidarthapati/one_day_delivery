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
            // Origin dock arrival. HANDED_TO_PICKUP_VAN = VAN_MEETING; RETURNED_TO_HUB = HUB_RETURN
            // (DA carried it, tapped handoff → RETURNED_TO_HUB, now the dock scan confirms arrival).
            // AT_ORIGIN_HUB tolerates a re-scan (M6 may have already in-scanned it).
            case HANDED_TO_PICKUP_VAN, RETURNED_TO_HUB, AT_ORIGIN_HUB -> VAN;
            case AWAITING_SELF_DROP -> SELF_DROP;
            case LANDED, DISPATCHED_TO_HUB, AT_DEST_HUB -> AIRPORT;
            // Shift-close carry-back (SC1): a DA brings an undelivered in-hand parcel back to the dest hub.
            // It re-enters the destination inbound sort exactly like a fresh arrival (→ territory bag).
            case COLLECTED_FROM_HUB, DROP_COLLECTED -> AIRPORT;
            default -> throw new UndeterminedArrivalException(state);
        };
    }
}
