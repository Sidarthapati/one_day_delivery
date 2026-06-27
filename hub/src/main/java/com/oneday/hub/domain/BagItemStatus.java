package com.oneday.hub.domain;

/** Whether a parcel is currently in its bag (append-only history; never deleted). */
public enum BagItemStatus {
    IN_BAG,
    REMOVED
}
