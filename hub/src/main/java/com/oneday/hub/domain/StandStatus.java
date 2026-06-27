package com.oneday.hub.domain;

/** Stand occupancy state. */
public enum StandStatus {
    OPEN,    // accepting parcels
    FULL,    // at capacity → triggers reassignment (§7.2, H2)
    CLOSED   // out of service
}
