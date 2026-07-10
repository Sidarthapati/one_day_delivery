package com.oneday.hub.domain;

/** Stand occupancy state. A stand has no flight/delivery "kind" — only whether it's free. */
public enum StandStatus {
    OPEN,      // free / accepting a bag
    OCCUPIED,  // a bag currently sits on it (at capacity → reassignment, §7.2, H2)
    CLOSED     // out of service
}
