package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.events.FlightEvent;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M9 flight events from the {@code oneday.flight.events} exchange (queue
 * {@code orders.flight}) and drives the M4 state machine. All three are INTERCITY-only legs.
 * Until M9 produces, the queue stays empty.
 */
@Component
public class FlightEventsConsumer {

    private static final String SOURCE = "m9-flight-consumer";

    private final ShipmentStateMachine stateMachine;

    FlightEventsConsumer(ShipmentStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @RabbitListener(queues = OrdersMessagingTopology.FLIGHT_QUEUE)
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
