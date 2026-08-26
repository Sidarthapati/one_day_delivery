package com.oneday.exceptions.domain;

/** Lifecycle of a support ticket. RESOLVED and CANCELLED are terminal (they stamp resolved_at). */
public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CANCELLED;

    public boolean isTerminal() {
        return this == RESOLVED || this == CANCELLED;
    }
}
