package com.oneday.common.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Single shared producer. Every module injects this instead of wiring KafkaTemplate + try/catch
 * by hand. Keys the message by the event's {@link DomainEvent#partitionKey()} so per-entity
 * ordering is automatic, and never lets a broker hiccup break the calling business flow
 * (a failed send is logged, not thrown — Kafka is best-effort for the producing module).
 *
 * Usage:  eventPublisher.publish(KafkaTopics.CRON_EVENTS, cronDepartedEvent);
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    EventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(String topic, DomainEvent event) {
        try {
            kafkaTemplate.send(topic, event.partitionKey(), event);
        } catch (Exception e) {
            log.warn("Kafka publish failed — topic={} type={} key={}: {}",
                    topic, event.eventTypeName(), event.partitionKey(), e.getMessage());
        }
    }
}
