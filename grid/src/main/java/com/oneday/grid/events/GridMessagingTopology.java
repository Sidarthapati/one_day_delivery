package com.oneday.grid.events;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.RabbitStreamSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M3's RabbitMQ topology: the GRID_EVENTS exchange it produces to (alerts), and the queue it
 * consumes M4's tile-queue-depth feed on. Declared on boot by Spring's RabbitAdmin.
 */
@Configuration
public class GridMessagingTopology {

    /** M3's queue on M4's tile-queue-depth exchange. */
    public static final String TILE_QUEUE_DEPTH_QUEUE = "grid.tile-queue-depth";

    /** Exchange M3 publishes TILE_OVERLOAD_ALERT / NO_DA_ALERT to (consumers join later). */
    @Bean
    Declarables gridEventsExchange() {
        return RabbitStreamSupport.exchange(EventStreams.GRID_EVENTS);
    }

    /** Consume M4's tile-queue-depth events. */
    @Bean
    Declarables tileQueueDepthBinding() {
        return RabbitStreamSupport.consumer(TILE_QUEUE_DEPTH_QUEUE, EventStreams.TILE_QUEUE_DEPTH);
    }
}
