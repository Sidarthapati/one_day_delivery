package com.oneday.exceptions.domain;

/** Where a case is in the problem-solve workflow. Terminal: RESOLVED, CANCELLED. */
public enum ExceptionStatus {
    OPEN,
    IN_PROGRESS,
    RESCHEDULED,
    RTO,
    RESOLVED,
    CANCELLED
}
