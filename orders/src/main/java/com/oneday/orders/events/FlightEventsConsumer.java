package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.KafkaTopics;
import com.oneday.common.kafka.events.FlightEvent;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M9 flight events from {@code oneday.flight.events} and drives the M4 state machine.
 * All three are INTERCITY-only legs. Dormant by default ({@code autoStartup=false}) until M9
 * produces on this topic.
 */
@Component
public class FlightEventsConsumer {

    private static final String SOURCE = "m9-flight-consumer";

    private final ShipmentStateMachine stateMachine;

    FlightEventsConsumer(ShipmentStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @KafkaListener(topics = KafkaTopics.FLIGHT_EVENTS, groupId = "orders-service",
            autoStartup = "${orders.kafka.consumer.auto-startup:false}")
    public void onFlightEvent(FlightEvent event) {
        ShipmentState target = switch (event.eventType()) {
            case DEPARTED       -> ShipmentState.DEPARTED;
            case LANDED         -> ShipmentState.LANDED;
            case RTO_IN_TRANSIT -> ShipmentState.RTO_IN_TRANSIT;
        };
        stateMachine.transition(event.shipmentId(), target,
                TransitionContext.fromKafka(SOURCE, String.valueOf(event.shipmentId())));
    }
}
