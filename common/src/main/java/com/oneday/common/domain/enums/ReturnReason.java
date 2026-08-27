package com.oneday.common.domain.enums;

/**
 * Why a return-to-sender was started. A return is modelled as a NEW child shipment ({@code <ref>_R})
 * that flows the normal pipeline backwards — this reason is the single extensible entry point's
 * classifier (see the orders {@code ReturnService.initiateReturn}).
 */
public enum ReturnReason {
    /** The 3 delivery attempts (M11 cap) were used up — the standard "returned to sender". */
    ATTEMPTS_EXHAUSTED,
    /** Ops declared the parcel undeliverable before the attempts ran out (bad address, refused, etc.). */
    UNDELIVERABLE,
    /** A cancellation landed after the parcel was already in custody — send it back rather than deliver. */
    POST_CUSTODY_CANCEL
}
