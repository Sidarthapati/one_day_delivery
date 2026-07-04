package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.events.HubEvent;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(HubEventsConsumer.class);
    private static final String SOURCE = "m7-hub-consumer";

    private final ShipmentStateMachine stateMachine;

    HubEventsConsumer(ShipmentStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    /**
     * The hub exchange carries more than M4's {@link HubEvent}s — notably M6's per-parcel
     * {@code ParcelSortedForDeliveryEvent} (which M6's binder consumes). We take the {@link DomainEvent}
     * supertype and act only on {@code HubEvent}, ignoring the rest, so foreign payloads are deserialized
     * and dropped instead of being rejected to the DLQ.
     */
    @RabbitListener(queues = OrdersMessagingTopology.HUB_QUEUE)
    public void onHubEvent(DomainEvent event) {
        if (!(event instanceof HubEvent hub)) {
            log.debug("Hub-exchange event {} is not an M4 HubEvent — ignoring", event.eventTypeName());
            return;
        }
        ShipmentState target = switch (hub.eventType()) {
            case STAND_ASSIGNED     -> ShipmentState.ORIGIN_HUB_PROCESSING;
            case BAG_CREATED        -> ShipmentState.IN_TAKEOFF_BAG;
            case SAMECITY_OUTBOUND  -> ShipmentState.HANDED_TO_DROP_VAN;   // SAME_CITY branch from IN_TAKEOFF_BAG
            case DEST_SORT_COMPLETE -> ShipmentState.DEST_HUB_PROCESSING;
            case DROP_VAN_HANDOFF   -> ShipmentState.HANDED_TO_DROP_VAN;   // DA_DELIVERY branch from DEST_HUB_PROCESSING
            // Forward-compatible with M7's expanded HubEventType (PARCEL_SORTED_FOR_DELIVERY, BAG_SEALED,
            // MANIFEST_GENERATED, BAG_RESCHEDULED, HUB_OVERLOAD_ALERT, HUB_DISCREPANCY): those don't drive
            // an M4 transition — ignore so the enum can grow without breaking this switch/consumer.
            default -> null;
        };
        if (target == null) {
            log.debug("Hub event {} for shipment {} drives no M4 transition — ignoring",
                    hub.eventType(), hub.shipmentId());
            return;
        }
        stateMachine.transition(hub.shipmentId(), target,
                TransitionContext.fromKafka(SOURCE, String.valueOf(hub.shipmentId())));
    }
}
