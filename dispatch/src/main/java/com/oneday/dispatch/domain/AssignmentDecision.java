package com.oneday.dispatch.domain;

/** The outcome recorded in the append-only assignment audit for every assignment attempt. */
public enum AssignmentDecision {
    ASSIGNED,
    CROSS_TERRITORY_ASSIGNED,
    DEFERRED_NO_DA,
    DEFERRED_CRON,
    DEFERRED_FROZEN
}
