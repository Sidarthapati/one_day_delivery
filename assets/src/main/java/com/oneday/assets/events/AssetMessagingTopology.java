package com.oneday.assets.events;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.RabbitStreamSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M13's RabbitMQ topology: the ASSET_EVENTS exchange it produces to. No consumer queues yet — the
 * downstream inbox (M11 overdue/lost) and van registry (M6) are deferred.
 */
@Configuration
public class AssetMessagingTopology {

    @Bean
    Declarables assetEventsExchange() {
        return RabbitStreamSupport.exchange(EventStreams.ASSET_EVENTS);
    }
}
