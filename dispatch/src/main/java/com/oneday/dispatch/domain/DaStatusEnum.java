package com.oneday.dispatch.domain;

/**
 * A DA's operational state during a shift. CRON_LOCKED → AT_CRON is driven by GPS proximity to the
 * cron vertex; ABSENT is set when the GPS heartbeat lapses. ON_BREAK means the DA is temporarily
 * unavailable on an approved disposition (break / auxiliary) with his territory held — the heartbeat
 * ABSENT sweep skips it, but the cron freeze still overrides it (cron wins over a break).
 */
public enum DaStatusEnum {
    OFFLINE,
    IDLE,
    IN_PROGRESS,
    CRON_LOCKED,
    AT_CRON,
    ABSENT,
    ON_BREAK
}
