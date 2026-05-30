package com.oneday.common.kafka;

/**
 * Contract every Kafka event payload implements so the shared infra (EventPublisher,
 * error handling, logging) can treat any event uniformly without knowing its concrete type.
 *
 * Keep payloads as plain data (records, or Lombok POJOs) — no behaviour beyond these two
 * derivations. The event TYPE (e.g. CREATED, NO_DA_ALERT) stays a per-module enum field on
 * the payload; eventTypeName() just exposes its name for logging/DLQ headers.
 */
public interface DomainEvent {

    /** Kafka message key — the entity this event is about (shipmentId, tileId, …).
     *  Same key ⇒ same partition ⇒ ordered per entity. Never null. */
    String partitionKey();

    /** The event type as a string (usually {@code someEnum.name()}) — for logging and DLQ headers. */
    String eventTypeName();
}
