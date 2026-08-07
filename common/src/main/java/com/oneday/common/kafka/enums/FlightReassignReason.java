package com.oneday.common.kafka.enums;

/**
 * Why M9 reassigned parcels to a new flight (rides on {@code FlightReassignedEvent}). M7 executes
 * the move the same way regardless — the reason is for audit / M10 leg re-baselining (M7-D-006).
 */
public enum FlightReassignReason {
    OPTIMISATION,      // cost/volume rebalancing (fill cheaper belly space, empty an underweight bag)
    CANCELLATION,      // the assigned flight was cancelled; M9 supplies the replacement
    CAPACITY,          // the assigned flight ran out of space
    PREPONE_OVERFLOW,  // a preponement left these parcels behind their old cutoff
    DELAY              // the assigned flight was delayed enough to break the delivery promise
}
