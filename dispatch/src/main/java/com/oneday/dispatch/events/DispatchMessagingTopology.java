package com.oneday.dispatch.events;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.RabbitStreamSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M5's RabbitMQ topology: the {@code DA_EVENTS} exchange it produces to, plus a single queue on M4's
 * {@code SHIPMENTS_EVENTS} carrying the whole shipment lifecycle (CREATED / STATE_CHANGED / CANCELLED).
 *
 * <p>One queue, consumed in arrival order, then dispatched by event type in {@code ShipmentEventsConsumer}
 * — this keeps a shipment's lifecycle events strictly ordered (no risk of handling STATE_CHANGED
 * before CREATED). Going to per-type queues later is a topology change, not a code rewrite. Declared
 * on boot by RabbitAdmin.</p>
 */
@Configuration
public class DispatchMessagingTopology {

    /** M5's queue for the full shipment lifecycle stream. */
    public static final String SHIPMENTS_QUEUE = "m5.shipments";

    /** M5's queue on M6's cron stream (M5 acts only on DA_CRON_SCHEDULED; the rest are ignored). */
    public static final String CRON_QUEUE = "m5.cron";

    /** Exchange M5 publishes DA lifecycle events to (gated — see DaEventProducer). */
    @Bean
    Declarables daEventsExchange() {
        return RabbitStreamSupport.exchange(EventStreams.DA_EVENTS);
    }

    // Unique bean names — a bare `shipmentsBinding`/`cronBinding` collides with M7/M4's identically-named
    // beans and (under bean-override) silently drops one module's queue.
    /** All shipment events (CREATED / STATE_CHANGED / CANCELLED) on one ordered queue. */
    @Bean
    Declarables m5ShipmentsBinding() {
        return RabbitStreamSupport.consumer(SHIPMENTS_QUEUE, EventStreams.SHIPMENTS_EVENTS);
    }

    /** All cron events on one queue; the consumer acts only on DA_CRON_SCHEDULED. */
    @Bean
    Declarables m5CronBinding() {
        return RabbitStreamSupport.consumer(CRON_QUEUE, EventStreams.CRON_EVENTS);
    }
}
