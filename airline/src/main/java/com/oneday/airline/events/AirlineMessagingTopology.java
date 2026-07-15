package com.oneday.airline.events;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.RabbitStreamSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M9's RabbitMQ topology: the FLIGHT_EVENTS exchange it produces to (M7's dormant {@code hub.flight}
 * queue and M4's live {@code orders.flight} queue already bind to it), plus its consumer queue on
 * M7's hub-events stream (the BAG_SEALED booking trigger, §6).
 */
@Configuration
public class AirlineMessagingTopology {

    /** M9's queue on M7's hub exchange — BAG_SEALED is the sole reaction M9 takes here (§6). */
    public static final String HUB_QUEUE = "airline.hub";

    @Bean
    Declarables flightEventsExchange() {
        return RabbitStreamSupport.exchange(EventStreams.FLIGHT_EVENTS);
    }

    @Bean
    Declarables hubBinding() {
        return RabbitStreamSupport.consumer(HUB_QUEUE, EventStreams.HUB_EVENTS);
    }
}
