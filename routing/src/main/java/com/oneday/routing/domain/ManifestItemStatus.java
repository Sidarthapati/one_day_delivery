package com.oneday.routing.domain;

/** Per-parcel manifest item status as it moves through the custody points (§11.2, M6-D-014). */
public enum ManifestItemStatus {
    PLANNED,
    LOADED,
    ONBOARD,
    HANDED_OFF,
    RECONCILED,
    EXCEPTION
}
