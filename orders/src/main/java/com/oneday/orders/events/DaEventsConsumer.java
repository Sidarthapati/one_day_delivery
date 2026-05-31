package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.KafkaTopics;
import com.oneday.common.kafka.events.DaEvent;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M5 DA events from {@code oneday.da.events} and drives the M4 state machine.
 *
 * <p>Dormant by default ({@code autoStartup=false}) until M5 produces on this topic — set
 * {@code orders.kafka.consumer.auto-startup=true} to enable. Side-effects (pickup-OTP generation
 * on {@code PICKUP_ASSIGNED}) are deferred until the base state flow is proven — see TODO.</p>
 */
@Component
public class DaEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(DaEventsConsumer.class);
    private static final String SOURCE = "m5-da-consumer";

    private final ShipmentStateMachine stateMachine;

    DaEventsConsumer(ShipmentStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @KafkaListener(topics = KafkaTopics.DA_EVENTS, groupId = "orders-service",
            autoStartup = "${orders.kafka.consumer.auto-startup:false}")
    public void onDaEvent(DaEvent event) {
        ShipmentState target = switch (event.eventType()) {
            case PICKUP_ASSIGNED       -> ShipmentState.PICKUP_ASSIGNED;     // TODO: generate + send pickup OTP
            case PICKUP_FAILED         -> ShipmentState.PICKUP_FAILED;
            case VAN_HANDOFF_COMPLETED -> ShipmentState.HANDED_TO_PICKUP_VAN;
            case DROP_ASSIGNED         -> ShipmentState.DROP_ASSIGNED;
            case DROP_COLLECTED        -> ShipmentState.DROP_COLLECTED;
            case DROP_COMPLETED        -> ShipmentState.DROPPED;
            case DROP_FAILED           -> ShipmentState.DELIVERY_FAILED;
            // Not consumed by M4 — PICKED_UP is driven exclusively by the OTP verify endpoint.
            case PICKUP_COMPLETED      -> null;
        };
        if (target == null) {
            log.debug("DA event {} ignored for shipment {}", event.eventType(), event.shipmentId());
            return;
        }
        stateMachine.transition(event.shipmentId(), target,
                TransitionContext.fromKafka(SOURCE, String.valueOf(event.shipmentId())));
    }
}
