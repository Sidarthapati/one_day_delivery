package com.oneday.common.kafka;

/**
 * Contract every event payload implements so the shared infra (EventPublisher, logging) can
 * treat any event uniformly without knowing its concrete type.
 *
 * Keep payloads as plain data (records, or Lombok POJOs) — no behaviour beyond these two
 * derivations. The event TYPE (e.g. CREATED, NO_DA_ALERT) stays a per-module enum field on
 * the payload; {@link #eventTypeName()} exposes its name — used as the RabbitMQ routing key.
 */
public interface DomainEvent {

    /** The entity this event is about (shipmentId, tileId, …) — for logging/correlation. Never null. */
    String partitionKey();

    /** The event type as a string (usually {@code someEnum.name()}) — used as the routing key. */
    String eventTypeName();
}
