package com.oneday.dispatch.domain;

/**
 * Lifecycle of a single queued task. FAILED and CANCELLED are the terminal states excluded from the
 * partial unique index (a parcel can be re-assigned after a failed attempt).
 */
public enum TaskStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    DEFERRED,
    CANCELLED
}
