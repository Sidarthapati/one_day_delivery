package com.oneday.orders.domain.enums;

/**
 * Category for a user's saved address, mirroring the consumer-app convention
 * (House / Office / Other). Purely a display/grouping label.
 */
public enum AddressLabel {
    HOME,
    OFFICE,
    OTHER,
    /** A B2B pickup warehouse — a saved origin the seller ships from repeatedly. */
    WAREHOUSE
}
