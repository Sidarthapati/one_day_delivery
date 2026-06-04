package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.KafkaTopics;
import com.oneday.common.kafka.events.ExceptionsEvent;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M11 exception events from {@code oneday.exceptions.events} and drives the M4 state
 * machine (RTO and reschedule flows). Dormant by default ({@code autoStartup=false}) until M11
 * produces on this topic.
 */
@Component
public class ExceptionsEventsConsumer {

    private static final String SOURCE = "m11-exception-consumer";

    private final ShipmentStateMachine stateMachine;

    ExceptionsEventsConsumer(ShipmentStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @KafkaListener(topics = KafkaTopics.EXCEPTIONS_EVENTS, groupId = "orders-service",
            autoStartup = "${orders.kafka.consumer.auto-startup:false}")
    public void onExceptionsEvent(ExceptionsEvent event) {
        ShipmentState target = switch (event.eventType()) {
            case RTO_INITIATED        -> ShipmentState.RTO_INITIATED;
            case PICKUP_RESCHEDULED   -> ShipmentState.PICKUP_ASSIGNED;
            case DELIVERY_RESCHEDULED -> ShipmentState.DROP_ASSIGNED;
            case RTO_COMPLETED        -> ShipmentState.RTO_COMPLETED;
        };
        stateMachine.transition(event.shipmentId(), target,
                TransitionContext.fromKafka(SOURCE, String.valueOf(event.shipmentId())));
    }
}
