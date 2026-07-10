package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.events.hub.DestSortCompleteEvent;
import com.oneday.common.kafka.events.hub.HubEventPayload;
import com.oneday.common.kafka.events.hub.SameCityOutboundEvent;
import com.oneday.common.kafka.events.hub.StandAssignedEvent;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes M7 hub events from the {@code oneday.hub.events} exchange (queue {@code orders.hub})
 * and drives the M4 state machine.
 *
 * <p>The queue {@code #}-binds the exchange, so it receives <em>every</em> hub payload shape. We take
 * the sealed base {@link HubEventPayload} (the {@code __TypeId__} header deserializes each message to
 * its concrete subtype, which is assignable to the base) and discriminate on the
 * {@link HubEventPayload#eventType() eventType} discriminator carried in the body — the project's
 * standing consumer pattern (mirrors M5's {@code ShipmentEventsConsumer} and M6's {@code HubFeedConsumer}).
 * Declaring a foreign reader type as the listener param (the old {@code HubEvent}) made the converter
 * fail every real message → DLQ; taking the base fixes that.</p>
 *
 * <p>Only the three per-parcel events that map to an M4 transition are acted on; bag-/wave-level and
 * M6/M9/M10-owned events (BAG_CREATED, PARCEL_SORTED_FOR_DELIVERY, BAG_SEALED, …) carry no M4 parcel
 * transition and are ignored. A transition that is illegal for the parcel's current state (a duplicate
 * or out-of-order hub event) is logged and acked, never dead-lettered.</p>
 */
@Component
public class HubEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(HubEventsConsumer.class);
    private static final String SOURCE = "m7-hub-consumer";

    private final ShipmentStateMachine stateMachine;

    HubEventsConsumer(ShipmentStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @RabbitListener(queues = OrdersMessagingTopology.HUB_QUEUE)
    public void onHubEvent(HubEventPayload event) {
        UUID shipmentId;
        ShipmentState target;
        switch (event.eventType()) {
            case STAND_ASSIGNED -> {
                shipmentId = ((StandAssignedEvent) event).shipmentId();
                target = ShipmentState.ORIGIN_HUB_PROCESSING;
            }
            case SAMECITY_OUTBOUND -> {   // SAME_CITY branch from IN_TAKEOFF_BAG
                shipmentId = ((SameCityOutboundEvent) event).shipmentId();
                target = ShipmentState.HANDED_TO_DROP_VAN;
            }
            case DEST_SORT_COMPLETE -> {  // parcelId == shipmentId in v1
                shipmentId = ((DestSortCompleteEvent) event).parcelId();
                target = ShipmentState.DEST_HUB_PROCESSING;
            }
            // Bag-/wave-level or M6/M9/M10-owned hub events carry no M4 parcel-state transition:
            // BAG_CREATED/BAG_SEALED/MANIFEST_GENERATED/BAG_RESCHEDULED → M9/M10; HUB_OVERLOAD_ALERT →
            // M10/station mgr; HUB_DISCREPANCY → M11/M10; PARCEL_SORTED_FOR_DELIVERY → M6 (loop binding).
            default -> { return; }
        }
        if (shipmentId == null) {
            return;
        }
        try {
            stateMachine.transition(shipmentId, target,
                    TransitionContext.fromKafka(SOURCE, String.valueOf(shipmentId)));
        } catch (IllegalStateTransitionException e) {
            // Duplicate / out-of-order hub event for a parcel already past this state — benign, ack it.
            log.debug("Hub event {} → {} not applicable to shipment {}: {}",
                    event.eventType(), target, shipmentId, e.getMessage());
        }
    }
}
