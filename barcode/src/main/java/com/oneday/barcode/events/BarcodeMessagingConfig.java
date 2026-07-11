package com.oneday.barcode.events;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.RabbitStreamSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares M8's producing exchange so publishes aren't dropped before any consumer binds. Idempotent
 * with orders' consumer-side declaration of the same durable topic exchange (RabbitAdmin dedups).
 */
@Configuration
class BarcodeMessagingConfig {

    @Bean
    Declarables scanEventsExchange() {
        return RabbitStreamSupport.exchange(EventStreams.SCAN_EVENTS);
    }
}
