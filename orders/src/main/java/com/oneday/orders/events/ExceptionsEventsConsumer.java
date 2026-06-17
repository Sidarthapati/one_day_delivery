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
            case RTO_COMPLETED        -> ShipmentState.RTO_COMPLETED;
        };
        stateMachine.transition(event.shipmentId(), target,
                TransitionContext.fromKafka(SOURCE, String.valueOf(event.shipmentId())));
    }
}
