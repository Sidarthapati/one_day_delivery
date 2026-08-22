package com.oneday.exceptions.domain;

/**
 * The failure reason taxonomy. M5 sends a free-text {@code reasonCode} string on the DA event; we map
 * it best-effort onto this enum (unknown → {@link #OTHER}), turning "failures are free-text" into a
 * reportable dimension. Extend as the ops vocabulary grows.
 */
public enum ExceptionReason {
    CUSTOMER_UNAVAILABLE,
    ADDRESS_INCORRECT,
    CUSTOMER_REFUSED,
    COD_NOT_READY,
    DA_NO_SHOW,
    CRON_MISSED,
    FLIGHT_MISSED,
    PARCEL_DAMAGED,
    OTHER,
    UNKNOWN;

    /** Best-effort map of M5's free-text reason string onto the taxonomy. Null/unrecognised → UNKNOWN. */
    public static ExceptionReason fromCode(String code) {
        if (code == null || code.isBlank()) {
            return UNKNOWN;
        }
        String c = code.trim().toUpperCase();
        for (ExceptionReason r : values()) {
            if (r.name().equals(c)) {
                return r;
            }
        }
        // A few known aliases from the M5 vocabulary.
        return switch (c) {
            case "PICKUP_FAILED", "DROP_FAILED", "DELIVERY_FAILED" -> OTHER; // a bare failure, no reason given
            case "NOT_AVAILABLE", "CUSTOMER_NOT_AVAILABLE", "NO_ONE_HOME" -> CUSTOMER_UNAVAILABLE;
            case "WRONG_ADDRESS", "BAD_ADDRESS", "ADDRESS_NOT_FOUND" -> ADDRESS_INCORRECT;
            case "REFUSED", "REJECTED" -> CUSTOMER_REFUSED;
            case "COD_NOT_PAID", "NO_CASH" -> COD_NOT_READY;
            case "DAMAGED", "DAMAGE" -> PARCEL_DAMAGED;
            default -> OTHER;
        };
    }
}
