package com.oneday.assets.domain;

/** The kind of custody exchange recorded in the append-only ledger and published on asset events. */
public enum AssetEventType {
    REGISTERED,
    ISSUED,
    RETURNED,
    TRANSFERRED,
    ACKNOWLEDGED,
    SENT_TO_MAINTENANCE,
    RETURNED_FROM_MAINTENANCE,
    REPORTED_LOST,
    REPORTED_DAMAGED,
    RECOVERED,
    DECOMMISSIONED
}
