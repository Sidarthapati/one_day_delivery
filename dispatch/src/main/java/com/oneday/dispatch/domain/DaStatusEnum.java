package com.oneday.dispatch.domain;

/**
 * A DA's operational state during a shift. CRON_LOCKED → AT_CRON is driven by GPS proximity to the
 * cron vertex; ABSENT is set when the GPS heartbeat lapses.
 */
public enum DaStatusEnum {
    OFFLINE,
    IDLE,
    IN_PROGRESS,
    CRON_LOCKED,
    AT_CRON,
    ABSENT
}
