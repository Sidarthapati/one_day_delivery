package com.oneday.hub.events;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.RabbitStreamSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M7's RabbitMQ topology: the HUB_EVENTS exchange it produces to, plus its consumer queue on M9's
 * flight stream. Hub sortation is driven by the physical dock scan ({@code HubReceivingService}) — the
 * single scan deterministically derives everything — so M7 does not listen to M4 state changes.
 */
@Configuration
public class HubMessagingTopology {

    /** M7's queue on M9's flight exchange — FLIGHT_REASSIGNED (§9) + advisory FLIGHT_TIME_CHANGED (§10). */
    public static final String FLIGHT_QUEUE = "hub.flight";

    @Bean
    Declarables hubEventsExchange() {
        return RabbitStreamSupport.exchange(EventStreams.HUB_EVENTS);
    }

    @Bean
    Declarables flightBinding() {
        return RabbitStreamSupport.consumer(FLIGHT_QUEUE, EventStreams.FLIGHT_EVENTS);
    }
}
