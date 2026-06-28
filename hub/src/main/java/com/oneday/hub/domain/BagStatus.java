package com.oneday.hub.domain;

/** Flight-bag lifecycle (§7.2). */
public enum BagStatus {
    OPEN,         // accumulating parcels
    SEALED,       // frozen at cutoff, manifest generated
    DISPATCHED,   // on the airport shuttle
    HANDED_OVER   // accepted by the airline/GHA
}
