package com.oneday.common.kafka.enums;

public enum FlightEventType {
    DEPARTED,
    LANDED,
    RTO_IN_TRANSIT,

    // ── Consumed by M7 (hub) ──────────────────────────────────────────────
    /**
     * M9 has (re)assigned parcels to a flight — the sole reschedule trigger M7 executes (M7-D-006).
     * Cancellation, capacity bumps and preponement-overflow all surface as this, distinguished by
     * {@link FlightReassignReason}. There is no separate FLIGHT_CANCELLED — M9 emits the replacement.
     */
    FLIGHT_REASSIGNED,
    /** Advisory: a flight's departure/cutoff moved (delay or prepone) without moving parcels. */
    FLIGHT_TIME_CHANGED
}
