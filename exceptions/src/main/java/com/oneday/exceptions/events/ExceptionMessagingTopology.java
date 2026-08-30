package com.oneday.exceptions.events;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.RabbitStreamSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M11's RabbitMQ topology: the {@code oneday.exceptions.events} exchange it produces to (already
 * consumed by M4 orders + M10 SLA), plus the queues it consumes failure signals on — M4 shipment
 * state changes and M5 DA lifecycle events (which carry the failure reason M4 drops). Each queue is
 * {@code exceptions.<stream>} so M11 gets its own copy alongside the other modules' consumers.
 *
 * <p>Flight-missed capture (from M9/M10) is a later input — not wired in v1.</p>
 */
@Configuration
public class ExceptionMessagingTopology {

    public static final String SHIPMENTS_QUEUE = "exceptions.shipments";
    public static final String DA_QUEUE        = "exceptions.da";
    public static final String DELIVERY_CONFIRMATIONS_QUEUE = "exceptions.delivery_confirmations";

    /** Exchange M11 publishes reschedule / RTO events to (→ M4 orders, M10 SLA). */
    @Bean
    Declarables exceptionsEventsExchange() {
        return RabbitStreamSupport.exchange(EventStreams.EXCEPTIONS_EVENTS);
    }

    @Bean Declarables exceptionsShipmentsBinding() { return RabbitStreamSupport.consumer(SHIPMENTS_QUEUE, EventStreams.SHIPMENTS_EVENTS); }
    @Bean Declarables exceptionsDaBinding()        { return RabbitStreamSupport.consumer(DA_QUEUE, EventStreams.DA_EVENTS); }
    @Bean Declarables exceptionsDeliveryConfirmationsBinding() { return RabbitStreamSupport.consumer(DELIVERY_CONFIRMATIONS_QUEUE, EventStreams.DELIVERY_CONFIRMATIONS); }
}
