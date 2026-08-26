package com.oneday.dispatch.domain;

/** Lifecycle of a midday DA-absence reassignment plan. */
public enum AbsenceStatus {
    /** Previewed, awaiting approval (or the auto-approve timeout). */
    PENDING,
    /** Applied by a station manager. */
    APPLIED,
    /** Applied automatically after the auto-approve timeout lapsed. */
    AUTO_APPLIED,
    /** Withdrawn before it was applied. */
    CANCELLED
}
