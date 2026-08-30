package com.oneday.dispatch.domain;

/** Why a shipment could not be assigned to a DA and was parked for retry. */
public enum DeferReason {
    NO_DA_AVAILABLE,
    CRON_INFEASIBLE,
    CRON_LOCKED,
    DA_ABSENT,
    SHIFT_ENDED,
    // Redelivery: the receiver proactively rejected today's delivery and picked a next-day shift — the
    // delivery is parked for that day/shift (a courtesy reschedule, not a failed attempt).
    RECEIVER_REJECTED,
    // Redelivery: a delivery attempt failed and is re-parked for a later day/shift retry.
    DELIVERY_FAILED
}
