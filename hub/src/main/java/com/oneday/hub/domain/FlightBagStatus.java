package com.oneday.hub.domain;

/** Flight-bag lifecycle (§7.2). */
public enum FlightBagStatus {
    OPEN,         // accumulating parcels
    SEALED,       // frozen at cutoff, manifest generated
    DISPATCHED,   // on the airport shuttle
    HANDED_OVER,  // accepted by the airline/GHA
    CANCELLED     // emptied by an M9 flight reassignment (§9); its stand is freed
}
