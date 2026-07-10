package com.oneday.dispatch.domain;

/** Lifecycle of a DA's cron meeting for the day. */
public enum CronAssignmentStatus {
    SCHEDULED,
    COMPLETED,
    MISSED,
    CANCELLED
}
