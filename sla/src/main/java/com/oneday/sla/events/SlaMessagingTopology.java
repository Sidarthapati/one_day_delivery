package com.oneday.sla.events;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.RabbitStreamSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M10's RabbitMQ topology: the {@code oneday.sla.events} exchange it produces to, plus the queues it
 * consumes lifecycle events on (each bound to a source module's exchange with a DLX/DLQ). Each queue
 * is {@code sla.<stream>} so M10 gets its own copy alongside the other modules' consumers.
 *
 * <p>Grid alerts (NO_DA / TILE_OVERLOAD) are a deferred input — their payloads live in the grid
 * module, not {@code common}, so M10 (self-contained, M10-D-007) does not couple to them in v1.</p>
 */
@Configuration
public class SlaMessagingTopology {

    public static final String SHIPMENTS_QUEUE  = "sla.shipments";
    public static final String HUB_QUEUE        = "sla.hub";
    public static final String CRON_QUEUE       = "sla.cron";
    public static final String FLIGHT_QUEUE     = "sla.flight";
    public static final String DA_QUEUE         = "sla.da";
    public static final String EXCEPTIONS_QUEUE = "sla.exceptions";

    /** Exchange M10 publishes escalations / breaches to (→ M11, ops/notification service). */
    @Bean
    Declarables slaEventsExchange() {
        return RabbitStreamSupport.exchange(EventStreams.SLA_EVENTS);
    }

    @Bean Declarables slaShipmentsBinding()  { return RabbitStreamSupport.consumer(SHIPMENTS_QUEUE, EventStreams.SHIPMENTS_EVENTS); }
    @Bean Declarables slaHubBinding()        { return RabbitStreamSupport.consumer(HUB_QUEUE, EventStreams.HUB_EVENTS); }
    @Bean Declarables slaCronBinding()       { return RabbitStreamSupport.consumer(CRON_QUEUE, EventStreams.CRON_EVENTS); }
    @Bean Declarables slaFlightBinding()     { return RabbitStreamSupport.consumer(FLIGHT_QUEUE, EventStreams.FLIGHT_EVENTS); }
    @Bean Declarables slaDaBinding()         { return RabbitStreamSupport.consumer(DA_QUEUE, EventStreams.DA_EVENTS); }
    @Bean Declarables slaExceptionsBinding() { return RabbitStreamSupport.consumer(EXCEPTIONS_QUEUE, EventStreams.EXCEPTIONS_EVENTS); }
}
