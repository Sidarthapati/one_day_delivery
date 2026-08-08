package com.oneday.common.kafka;

import com.oneday.common.log.AuditLog;
import com.oneday.common.log.CorrelationKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ adapter for {@link EventPublisher}. Publishes to the exchange named by {@code stream}
 * with the event type as the routing key, so a consuming queue can bind {@code #} (all types) or a
 * pattern. Best-effort: a broker hiccup is logged, never thrown — it must not break the (already
 * committed) business flow. See {@code docs/EVENT-BUS-ARCHITECTURE.md} §5.
 */
@Component
public class RabbitEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    RabbitEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(String stream, DomainEvent event) {
        try {
            // exchange = stream; routing key = event type; payload converted to JSON by the
            // shared Jackson2JsonMessageConverter (MessagingConfig).
            rabbitTemplate.convertAndSend(stream, event.eventTypeName(), event);

            // Audit the emit — the counterpart to event.consumed (RabbitConsumerMdcAspect); together
            // they make the otherwise-invisible RabbitMQ hop queryable by shipment/parcel.
            CorrelationKeys keys = CorrelationKeys.from(event);
            AuditLog.event("event.published")
                    .kv("stream", stream)
                    .kv("eventType", event.eventTypeName())
                    .kv("shipmentId", keys.shipmentId())
                    .kv("shipmentRef", keys.shipmentRef())
                    .kv("parcelId", keys.parcelId())
                    .log();
        } catch (Exception e) {
            log.warn("Rabbit publish failed — exchange={} type={} key={}: {}",
                    stream, event.eventTypeName(), event.partitionKey(), e.getMessage());
        }
    }
}
