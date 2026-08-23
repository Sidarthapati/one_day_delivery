package com.oneday.common.kafka.enums;

public enum ExceptionsEventType {
    RTO_INITIATED,
    PICKUP_RESCHEDULED,
    DELIVERY_RESCHEDULED,
    /** Re-run M5 delivery assignment (re-drives HANDED_TO_DROP_VAN) — unlike RESCHEDULE which only flips
     *  M4 to DROP_ASSIGNED (van-meeting redelivery, same DA). */
    DELIVERY_REASSIGNED,
    RTO_COMPLETED
}
