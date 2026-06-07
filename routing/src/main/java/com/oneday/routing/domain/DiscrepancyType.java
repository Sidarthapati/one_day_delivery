package com.oneday.routing.domain;

/** Reconciliation outcome at a handoff (§13.3, M6-D-018). */
public enum DiscrepancyType {
    NONE,
    MISSING,    // manifest parcel not scanned
    EXTRA,      // scanned parcel not on manifest (mis-route)
    REJECTED    // DA refuses (e.g. damaged)
}
