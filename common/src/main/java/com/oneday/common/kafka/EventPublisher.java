package com.oneday.common.kafka;

/**
 * The producer port. Every module injects this and calls {@code publish(stream, event)} —
 * it never touches the broker client directly. The active adapter (today
 * {@link RabbitEventPublisher}) decides the transport, so swapping RabbitMQ for another broker,
 * or adding a DB outbox, is an adapter change with zero business-code change. See
 * {@code docs/EVENT-BUS-ARCHITECTURE.md}.
 *
 * <p>Usage:  {@code eventPublisher.publish(EventStreams.CRON_EVENTS, daCronScheduledEvent);}</p>
 */
public interface EventPublisher {

    /**
     * Publish an event to a logical stream.
     *
     * @param stream a logical stream name — an {@link EventStreams} constant (a RabbitMQ exchange).
     * @param event  the payload; its {@link DomainEvent#eventTypeName()} becomes the routing key.
     */
    void publish(String stream, DomainEvent event);
}
