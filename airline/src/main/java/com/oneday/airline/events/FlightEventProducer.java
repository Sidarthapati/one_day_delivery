package com.oneday.airline.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.events.FlightEvent;
import com.oneday.common.kafka.enums.FlightEventType;
import com.oneday.common.kafka.events.flight.FlightReassignedEvent;
import com.oneday.common.kafka.events.flight.FlightTimeChangedEvent;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes M9's flight events on {@code oneday.flight.events} (§2, §7): the per-shipment
 * DEPARTED/LANDED notification to M4 (its {@code FlightEventsConsumer} is already live), and the
 * reassignment engine's FLIGHT_REASSIGNED/FLIGHT_TIME_CHANGED to M7 (its {@code FlightEventConsumer}
 * is now enabled).
 */
@Component
public class FlightEventProducer {

    private final EventPublisher eventPublisher;

    FlightEventProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void emitDeparted(UUID shipmentId) {
        publish(shipmentId, FlightEventType.DEPARTED);
    }

    public void emitLanded(UUID shipmentId) {
        publish(shipmentId, FlightEventType.LANDED);
    }

    public void emitReassigned(FlightReassignedEvent event) {
        eventPublisher.publish(EventStreams.FLIGHT_EVENTS, event);
    }

    public void emitTimeChanged(FlightTimeChangedEvent event) {
        eventPublisher.publish(EventStreams.FLIGHT_EVENTS, event);
    }

    private void publish(UUID shipmentId, FlightEventType eventType) {
        eventPublisher.publish(EventStreams.FLIGHT_EVENTS, new FlightEvent(shipmentId, eventType));
    }
}
