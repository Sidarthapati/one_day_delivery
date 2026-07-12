package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.ScanEvent;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes M8 scan events from the {@code oneday.scan.events} exchange (queue {@code orders.scan})
 * and drives the M4 state machine. Until M8 produces, the queue stays empty. ETA recalculation +
 * customer notification on {@code HUB_ORIGIN_IN}/{@code SELF_DROP_ACCEPTED} are deferred until the
 * base state flow is proven — see TODO.
 */
@Component
public class ScanEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(ScanEventsConsumer.class);
    private static final String SOURCE = "m8-scan-consumer";

    private final ShipmentStateMachine stateMachine;
    private final ShipmentRepository shipmentRepository;

    ScanEventsConsumer(ShipmentStateMachine stateMachine, ShipmentRepository shipmentRepository) {
        this.stateMachine = stateMachine;
        this.shipmentRepository = shipmentRepository;
    }

    @RabbitListener(queues = OrdersMessagingTopology.SCAN_QUEUE)
    @Transactional
    public void onScanEvent(ScanEvent event) {
        // LABEL_GENERATED is not a state transition — it carries the barcode M8 minted; stamp it.
        if (event.eventType() == ScanEventType.LABEL_GENERATED) {
            applyLabel(event);
            return;
        }
        ShipmentState target = switch (event.eventType()) {
            case HUB_ORIGIN_IN         -> ShipmentState.AT_ORIGIN_HUB;        // TODO: recalc ETA + notify customer
            case SELF_DROP_ACCEPTED    -> ShipmentState.AT_ORIGIN_HUB;        // TODO: recalc ETA + notify customer
            case HUB_ORIGIN_OUT        -> ShipmentState.DISPATCHED_TO_AIRPORT; // D-007
            case GHA_ACCEPTANCE        -> ShipmentState.AT_AIRPORT;
            case DEST_SHUTTLE_IN       -> ShipmentState.DISPATCHED_TO_HUB;    // D-007
            case HUB_DEST_IN           -> ShipmentState.AT_DEST_HUB;
            case HUB_COLLECT_COMPLETED -> ShipmentState.HUB_COLLECTED;
            // DELIVERED is a custody fact only (Option A) — DROPPED stays owned by the delivery-OTP
            // verify path (scan = right box, OTP = right customer, mirroring LABEL_GENERATED/PICKED_UP).
            case DELIVERED             -> null;
            case LABEL_GENERATED       -> null; // handled above
        };
        if (target == null) {
            log.debug("Scan event {} ignored for shipment {}", event.eventType(), event.shipmentId());
            return;
        }
        stateMachine.transition(event.shipmentId(), target,
                TransitionContext.fromKafka(SOURCE, String.valueOf(event.shipmentId())));
    }

    private void applyLabel(ScanEvent event) {
        if (event.parcelId() == null) {
            log.warn("LABEL_GENERATED for shipment {} carried no parcelId — ignoring", event.shipmentId());
            return;
        }
        shipmentRepository.findById(event.shipmentId()).ifPresentOrElse(s -> {
            s.setParcelId(event.parcelId());
            shipmentRepository.save(s);
            log.debug("Stamped parcelId {} on shipment {}", event.parcelId(), event.shipmentId());
        }, () -> log.warn("LABEL_GENERATED for unknown shipment {}", event.shipmentId()));
    }
}
