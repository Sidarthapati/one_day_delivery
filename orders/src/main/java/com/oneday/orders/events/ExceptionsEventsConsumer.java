package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.events.ExceptionsEvent;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M11 exception events from the {@code oneday.exceptions.events} exchange (queue
 * {@code orders.exceptions}) and drives the M4 state machine (RTO and reschedule flows).
 * Until M11 produces, the queue stays empty.
 */
@Component
public class ExceptionsEventsConsumer {

    private static final String SOURCE = "m11-exception-consumer";

    private final ShipmentStateMachine stateMachine;

    ExceptionsEventsConsumer(ShipmentStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @RabbitListener(queues = OrdersMessagingTopology.EXCEPTIONS_QUEUE)
    public void onExceptionsEvent(ExceptionsEvent event) {
        ShipmentState target = switch (event.eventType()) {
            case RTO_INITIATED        -> ShipmentState.RTO_INITIATED;
            case PICKUP_RESCHEDULED   -> ShipmentState.PICKUP_ASSIGNED;
            case DELIVERY_RESCHEDULED -> ShipmentState.DROP_ASSIGNED;
            // Reassign re-drives the M5 assignment trigger (the failed DELIVERY task is already terminal,
            // so dispatch assigns a fresh DA); reschedule above only flips M4 state (van-meeting redelivery).
            case DELIVERY_REASSIGNED  -> ShipmentState.HANDED_TO_DROP_VAN;
            case RTO_COMPLETED        -> ShipmentState.RTO_COMPLETED;
        };
        stateMachine.transition(event.shipmentId(), target,
                TransitionContext.fromKafka(SOURCE, String.valueOf(event.shipmentId())));
    }
}
