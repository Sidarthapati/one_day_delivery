package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.KafkaTopics;
import com.oneday.common.kafka.events.ScanEvent;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M8 scan events from {@code oneday.scan.events} and drives the M4 state machine.
 *
 * <p>Dormant by default ({@code autoStartup=false}) until M8 produces on this topic. ETA
 * recalculation + customer notification on {@code HUB_ORIGIN_IN}/{@code SELF_DROP_ACCEPTED}
 * are deferred until the base state flow is proven — see TODO.</p>
 */
@Component
public class ScanEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(ScanEventsConsumer.class);
    private static final String SOURCE = "m8-scan-consumer";

    private final ShipmentStateMachine stateMachine;

    ScanEventsConsumer(ShipmentStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @KafkaListener(topics = KafkaTopics.SCAN_EVENTS, groupId = "orders-service",
            autoStartup = "${orders.kafka.consumer.auto-startup:false}")
    public void onScanEvent(ScanEvent event) {
        ShipmentState target = switch (event.eventType()) {
            case HUB_ORIGIN_IN         -> ShipmentState.AT_ORIGIN_HUB;   // TODO: recalc ETA + notify customer
            case SELF_DROP_ACCEPTED    -> ShipmentState.AT_ORIGIN_HUB;   // TODO: recalc ETA + notify customer
            case GHA_ACCEPTANCE        -> ShipmentState.AT_AIRPORT;
            case HUB_DEST_IN           -> ShipmentState.AT_DEST_HUB;
            case HUB_COLLECT_COMPLETED -> ShipmentState.HUB_COLLECTED;
            // Not a state transition — carries the generated parcelId (sets shipment.parcel_id).
            // TODO: handle once ScanEvent is extended with the parcelId field.
            case LABEL_GENERATED       -> null;
        };
        if (target == null) {
            log.debug("Scan event {} ignored for shipment {}", event.eventType(), event.shipmentId());
            return;
        }
        stateMachine.transition(event.shipmentId(), target,
                TransitionContext.fromKafka(SOURCE, String.valueOf(event.shipmentId())));
    }
}
