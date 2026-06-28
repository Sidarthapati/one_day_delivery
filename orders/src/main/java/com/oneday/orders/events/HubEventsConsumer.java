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
            case DROP_VAN_HANDOFF   -> ShipmentState.HANDED_TO_DROP_VAN;   // DA_DELIVERY branch from DEST_HUB_PROCESSING (deprecated, M7-D-004)
            // Hub-internal / fan-out events that carry no M4 parcel-state transition:
            // PARCEL_SORTED_FOR_DELIVERY → M6 (loop binding); BAG_SEALED/MANIFEST_GENERATED → M9/M10;
            // BAG_RESCHEDULED → M9/M10; HUB_OVERLOAD_ALERT → M10/station mgr; HUB_DISCREPANCY → M11/M10.
            case PARCEL_SORTED_FOR_DELIVERY, BAG_SEALED, MANIFEST_GENERATED,
                 BAG_RESCHEDULED, HUB_OVERLOAD_ALERT, HUB_DISCREPANCY -> null;
        };
        // Bag-/wave-level hub events (e.g. BAG_CREATED) carry no shipmentId — there is no single
        // parcel to transition, so skip. Per-parcel events (STAND_ASSIGNED, …) always carry one.
        if (target == null || event.shipmentId() == null) {
            return;
        }
        stateMachine.transition(event.shipmentId(), target,
                TransitionContext.fromKafka(SOURCE, String.valueOf(event.shipmentId())));
    }
}
