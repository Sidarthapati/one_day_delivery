package com.oneday.exceptions.domain;

import com.oneday.common.kafka.enums.ExceptionsEventType;

/**
 * A problem-solve action a manager/agent takes on a case. Most actions drive M4 by publishing the
 * matching {@link ExceptionsEventType} to {@code oneday.exceptions.events} — the orders consumer
 * already turns each into a state transition (reschedule = re-assign; RTO = the return path).
 */
public enum ResolveAction {
    RESCHEDULE_PICKUP(ExceptionsEventType.PICKUP_RESCHEDULED),
    RESCHEDULE_DELIVERY(ExceptionsEventType.DELIVERY_RESCHEDULED),
    INITIATE_RTO(ExceptionsEventType.RTO_INITIATED),
    COMPLETE_RTO(ExceptionsEventType.RTO_COMPLETED),
    /** Close the case without moving the shipment — the issue was handled offline. */
    MARK_RESOLVED(null);

    private final ExceptionsEventType event;

    ResolveAction(ExceptionsEventType event) {
        this.event = event;
    }

    /** The M4-driving event to publish, or null when the action only closes the case. */
    public ExceptionsEventType event() {
        return event;
    }
}
