package com.oneday.dispatch.domain;

/** The outcome recorded in the append-only assignment audit for every assignment attempt. */
public enum AssignmentDecision {
    ASSIGNED,
    CROSS_TERRITORY_ASSIGNED,
    /** A station manager manually picked the DA (still cron-feasibility gated) rather than the
     *  cheapest-least-loaded automatic selection — distinguished here so the audit trail can tell
     *  a human override apart from an algorithmic one. */
    MANUAL_ASSIGNED,
    DEFERRED_NO_DA,
    DEFERRED_CRON,
    DEFERRED_FROZEN
}
