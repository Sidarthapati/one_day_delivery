package com.oneday.routing.events;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.RabbitStreamSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M6's RabbitMQ topology. M6 currently only produces cron/plan events on the CRON_EVENTS exchange
 * (consumers — M5, M10 — join later); declaring the exchange here ensures publishes aren't dropped
 * before any consumer queue exists. M6's own consumer queues (deliver/collect feeds from M7/M5)
 * arrive with PR5+.
 */
@Configuration
public class RoutingMessagingTopology {

    @Bean
    Declarables cronEventsExchange() {
        return RabbitStreamSupport.exchange(EventStreams.CRON_EVENTS);
    }
}
