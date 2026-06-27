package com.oneday.hub.domain;

/** How a parcel reached the dock (§6). */
public enum ArrivalMode {
    VAN,        // first-mile van unload (M6-originated VAN_UNLOAD, M7-D-005)
    SELF_DROP,  // customer brought it to the hub
    AIRPORT     // landed bag, shuttle back (destination side; PR #2)
}
