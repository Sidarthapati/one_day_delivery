package com.oneday.common.kafka.enums;

public enum CronEventType {
    DEPARTED_HUB,      // IN_TAKEOFF_BAG → DISPATCHED_TO_AIRPORT (INTERCITY)
    DEPARTED_AIRPORT   // LANDED → DISPATCHED_TO_HUB
}
