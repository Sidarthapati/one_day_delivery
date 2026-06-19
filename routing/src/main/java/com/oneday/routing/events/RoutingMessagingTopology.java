package com.oneday.routing.events;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.RabbitStreamSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M6's RabbitMQ topology: the CRON_EVENTS exchange it produces to, plus its consumer queues on M7's
 * hub feed and M5's DA feed. Until those modules publish, the queues simply stay empty.
 */
@Configuration
public class RoutingMessagingTopology {

    // M6's queue on M7's hub exchange — sorted-for-delivery parcels.
    public static final String HUB_FEED_QUEUE = "routing.hub";

    // M6's queue on M5's DA exchange — DA pickups (first-mile accumulation).
    public static final String DA_FEED_QUEUE = "routing.da";

    @Bean
    Declarables cronEventsExchange() {
        return RabbitStreamSupport.exchange(EventStreams.CRON_EVENTS);
    }

    @Bean
    Declarables hubFeedBinding() {
        return RabbitStreamSupport.consumer(HUB_FEED_QUEUE, EventStreams.HUB_EVENTS);
    }

    @Bean
    Declarables daFeedBinding() {
        return RabbitStreamSupport.consumer(DA_FEED_QUEUE, EventStreams.DA_EVENTS);
    }
}
