package com.oneday.orders.events;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.RabbitStreamSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M4's RabbitMQ topology: the SHIPMENTS_EVENTS exchange it produces to, and the six queues it
 * consumes other modules' events on (each bound to that module's exchange, each with a DLX/DLQ).
 * Declared on boot by Spring's RabbitAdmin. Queues for not-yet-built producers (M5/M7/M8/M9/M11)
 * simply stay empty until those modules publish.
 */
@Configuration
public class OrdersMessagingTopology {

    public static final String DA_QUEUE         = "orders.da";
    public static final String HUB_QUEUE        = "orders.hub";
    public static final String SCAN_QUEUE       = "orders.scan";
    public static final String FLIGHT_QUEUE     = "orders.flight";
    public static final String CRON_QUEUE       = "orders.cron";
    public static final String EXCEPTIONS_QUEUE = "orders.exceptions";

    /** Exchange M4 publishes shipment lifecycle events to (CREATED / STATE_CHANGED / CANCELLED). */
    @Bean
    Declarables shipmentsEventsExchange() {
        return RabbitStreamSupport.exchange(EventStreams.SHIPMENTS_EVENTS);
    }

    @Bean Declarables daBinding()         { return RabbitStreamSupport.consumer(DA_QUEUE, EventStreams.DA_EVENTS); }
    @Bean Declarables hubBinding()        { return RabbitStreamSupport.consumer(HUB_QUEUE, EventStreams.HUB_EVENTS); }
    @Bean Declarables scanBinding()       { return RabbitStreamSupport.consumer(SCAN_QUEUE, EventStreams.SCAN_EVENTS); }
    @Bean Declarables flightBinding()     { return RabbitStreamSupport.consumer(FLIGHT_QUEUE, EventStreams.FLIGHT_EVENTS); }
    @Bean Declarables cronBinding()       { return RabbitStreamSupport.consumer(CRON_QUEUE, EventStreams.CRON_EVENTS); }
    @Bean Declarables exceptionsBinding() { return RabbitStreamSupport.consumer(EXCEPTIONS_QUEUE, EventStreams.EXCEPTIONS_EVENTS); }
}
