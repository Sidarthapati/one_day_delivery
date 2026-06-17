package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.events.HubEvent;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M7 hub events from the {@code oneday.hub.events} exchange (queue {@code orders.hub})
 * and drives the M4 state machine. Until M7 produces, the queue stays empty.</p>
 *
 * <p>OD-9 (open): the event triggering {@code DEST_HUB_PROCESSING → AWAITING_HUB_COLLECT}
 * (HUB_COLLECT path) is not yet decided — recommended a new {@code HUB_COLLECT_STAGED} value in
 * {@code HubEventType}. No handler exists for it until the team confirms the event type.</p>
 */
@Component
public class HubEventsConsumer {

    private static final String SOURCE = "m7-hub-consumer";

    private final ShipmentStateMachine stateMachine;

    HubEventsConsumer(ShipmentStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @RabbitListener(queues = OrdersMessagingTopology.HUB_QUEUE)
    public void onHubEvent(HubEvent event) {
        ShipmentState target = switch (event.eventType()) {
            case STAND_ASSIGNED     -> ShipmentState.ORIGIN_HUB_PROCESSING;
            case BAG_CREATED        -> ShipmentState.IN_TAKEOFF_BAG;
            case SAMECITY_OUTBOUND  -> ShipmentState.HANDED_TO_DROP_VAN;   // SAME_CITY branch from IN_TAKEOFF_BAG
            case DEST_SORT_COMPLETE -> ShipmentState.DEST_HUB_PROCESSING;
            case DROP_VAN_HANDOFF   -> ShipmentState.HANDED_TO_DROP_VAN;   // DA_DELIVERY branch from DEST_HUB_PROCESSING
        };
        stateMachine.transition(event.shipmentId(), target,
                TransitionContext.fromKafka(SOURCE, String.valueOf(event.shipmentId())));
    }
}
