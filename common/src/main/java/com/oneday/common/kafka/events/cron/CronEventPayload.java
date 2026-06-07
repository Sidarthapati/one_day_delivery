package com.oneday.common.kafka.events.cron;

import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.CronEventType;

/**
 * Base contract for the typed payloads M6 (routing) publishes on {@code oneday.cron.events}
 * (M6 design §17.1). Sealed so the set of cron event shapes is closed and exhaustively handled.
 *
 * <p>Each payload is a plain record. {@link DomainEvent#partitionKey()} is the entity the event
 * is about (vanId for run-time events, cityId/daId for plan-time) so per-entity ordering holds.
 * Consumers that only need the discriminator can use the tolerant
 * {@link com.oneday.common.kafka.events.CronEvent} reader instead.</p>
 */
public sealed interface CronEventPayload extends DomainEvent permits
        DaCronScheduledEvent,
        ShuttleScheduledEvent,
        RoutePlanPublishedEvent,
        RouteChangedEvent,
        VanArrivedEvent,
        VanRunningLateEvent,
        HandoffCompletedEvent,
        HandoffDiscrepancyEvent,
        LoopOverflowEvent,
        VanBreakdownEvent {

    CronEventType eventType();

    @Override
    default String eventTypeName() {
        return eventType().name();
    }
}
