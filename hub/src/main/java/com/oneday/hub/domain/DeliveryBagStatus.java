package com.oneday.hub.domain;

/** Destination delivery-bag lifecycle (§8.1), the inbound mirror of {@link FlightBagStatus}. */
public enum DeliveryBagStatus {
    OPEN,         // accepting parcels on its stand
    SEALED,       // contents frozen, load-list manifest generated
    LOADED,       // M6's VAN_LOAD scan took the whole bag onto the van
    HANDED_OVER   // DA hub-collected the bag (no-van path)
}
