package com.oneday.exceptions.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.ExceptionsEventType;
import com.oneday.common.kafka.events.ExceptionsEvent;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes M11's outbound events on {@code oneday.exceptions.events}. This is the seam that was
 * always reserved but never driven: the orders {@code ExceptionsEventsConsumer} turns each event into
 * a state transition (reschedule / RTO), and SLA re-evaluates on the same stream.
 */
@Component
public class ExceptionEventProducer {

    private final EventPublisher eventPublisher;

    public ExceptionEventProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void publish(UUID shipmentId, ExceptionsEventType type) {
        eventPublisher.publish(EventStreams.EXCEPTIONS_EVENTS, new ExceptionsEvent(shipmentId, type));
    }
}
