package com.oneday.shuttle.domain;

/** Which hub↔airport leg a shuttle binding is for. */
public enum ShuttleDirection {
    /** Origin hub → airport (carrying a sealed flight bag out). */
    OUTBOUND,
    /** Destination airport → hub (carrying a landed flight's parcels in). */
    INBOUND
}
