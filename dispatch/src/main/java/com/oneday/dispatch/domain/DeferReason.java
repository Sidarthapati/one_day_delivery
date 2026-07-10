package com.oneday.dispatch.domain;

/** Why a shipment could not be assigned to a DA and was parked for retry. */
public enum DeferReason {
    NO_DA_AVAILABLE,
    CRON_INFEASIBLE,
    CRON_LOCKED,
    DA_ABSENT,
    SHIFT_ENDED
}
