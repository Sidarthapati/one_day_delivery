package com.oneday.common.kafka.enums;

/**
 * Discriminator for events M10 (SLA) publishes on {@code oneday.sla.events}
 * ({@link com.oneday.common.kafka.EventStreams#SLA_EVENTS}).
 */
public enum SlaEventType {
    /** A leg entered RED (or was raised to a higher level) — escalate. → Supervisor/Station Mgr, ops. */
    SLA_ESCALATION_RAISED,
    /** The internal target actually passed (or a hard failure) — a confirmed breach. → M11, Admin. */
    SLA_BREACHED
}
