package com.oneday.hub.events;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.RabbitStreamSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M7's RabbitMQ topology: the HUB_EVENTS exchange it produces to, plus its consumer queue on M4's
 * shipment stream (the AT_ORIGIN_HUB / AT_DEST_HUB sort triggers). The consumer stays dormant
 * (autoStartup=false) until M4 produces; until then M7 is driven via the REST API and in tests.
 */
@Configuration
public class HubMessagingTopology {

    /** M7's queue on M4's shipment exchange — hub-arrival state changes that trigger sortation. */
    public static final String SHIPMENTS_QUEUE = "hub.shipments";

    /** M7's queue on M9's flight exchange — FLIGHT_REASSIGNED (§9) + advisory FLIGHT_TIME_CHANGED (§10). */
    public static final String FLIGHT_QUEUE = "hub.flight";

    @Bean
    Declarables hubEventsExchange() {
        return RabbitStreamSupport.exchange(EventStreams.HUB_EVENTS);
    }

    @Bean
    Declarables shipmentsBinding() {
        return RabbitStreamSupport.consumer(SHIPMENTS_QUEUE, EventStreams.SHIPMENTS_EVENTS);
    }

    @Bean
    Declarables flightBinding() {
        return RabbitStreamSupport.consumer(FLIGHT_QUEUE, EventStreams.FLIGHT_EVENTS);
    }
}
