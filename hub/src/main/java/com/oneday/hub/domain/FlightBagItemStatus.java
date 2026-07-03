package com.oneday.hub.domain;

/** Whether a parcel is currently in its bag (append-only history; never deleted). */
public enum FlightBagItemStatus {
    IN_BAG,
    REMOVED
}
