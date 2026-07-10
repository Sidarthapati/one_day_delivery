package com.oneday.hub.domain;

/** The two directions the one symmetric sort engine runs in (M7-D-001). */
public enum SortDirection {
    OUTBOUND,   // origin hub: consolidate toward a flight
    INBOUND     // destination hub: de-consolidate toward a DA territory
}
