package com.oneday.common.kafka;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Layer-1 test pattern (no broker, no Docker): mock KafkaTemplate, assert the publisher
 * sends to the right topic with the event's partitionKey. This is the template every module's
 * producer test should copy.
 */
@ExtendWith(MockitoExtension.class)
class EventPublisherTest {

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    EventPublisher publisher;

    /** Minimal stand-in event so the test doesn't depend on any module's payloads. */
    record FakeEvent(String key, String type) implements DomainEvent {
        @Override public String partitionKey()  { return key; }
        @Override public String eventTypeName() { return type; }
    }

    @BeforeEach
    void setUp() {
        publisher = new EventPublisher(kafkaTemplate);
    }

    @Test
    void publish_sendsToTopicKeyedByPartitionKey() {
        FakeEvent event = new FakeEvent("P-123", "CREATED");

        publisher.publish("oneday.test.events", event);

        verify(kafkaTemplate).send(eq("oneday.test.events"), eq("P-123"), eq(event));
    }

    @Test
    void publish_brokerFailure_doesNotPropagate() {
        doThrow(new RuntimeException("broker down"))
                .when(kafkaTemplate).send(anyString(), anyString(), any());

        // Must complete without throwing — Kafka is best-effort for the producer.
        publisher.publish("oneday.test.events", new FakeEvent("P-123", "CREATED"));
    }
}
