package com.oneday.common.kafka;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Layer-1 test pattern (no broker, no Docker): mock RabbitTemplate, assert the publisher sends
 * to the right exchange with the event type as the routing key. This is the template every
 * module's producer test should copy.
 */
@ExtendWith(MockitoExtension.class)
class EventPublisherTest {

    @Mock
    RabbitTemplate rabbitTemplate;

    EventPublisher publisher;

    /** Minimal stand-in event so the test doesn't depend on any module's payloads. */
    record FakeEvent(String key, String type) implements DomainEvent {
        @Override public String partitionKey()  { return key; }
        @Override public String eventTypeName() { return type; }
    }

    @BeforeEach
    void setUp() {
        publisher = new RabbitEventPublisher(rabbitTemplate);
    }

    @Test
    void publish_sendsToExchangeKeyedByEventType() {
        FakeEvent event = new FakeEvent("P-123", "CREATED");

        publisher.publish("oneday.test.events", event);

        verify(rabbitTemplate).convertAndSend(eq("oneday.test.events"), eq("CREATED"), eq((Object) event));
    }

    @Test
    void publish_brokerFailure_doesNotPropagate() {
        doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Must complete without throwing — the broker is best-effort for the producer.
        publisher.publish("oneday.test.events", new FakeEvent("P-123", "CREATED"));
    }
}
