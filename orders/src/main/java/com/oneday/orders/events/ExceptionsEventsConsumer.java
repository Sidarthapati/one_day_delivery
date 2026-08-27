package com.oneday.orders.events;

import com.oneday.common.domain.enums.ReturnReason;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.enums.ExceptionsEventType;
import com.oneday.common.kafka.events.ExceptionsEvent;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ReturnService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M11 exception events from the {@code oneday.exceptions.events} exchange (queue
 * {@code orders.exceptions}) and drives the M4 state machine (RTO and reschedule flows).
 * Until M11 produces, the queue stays empty.
 *
 * <p>RTO_INITIATED no longer just flips a state — it spawns a real return child shipment via
 * {@link ReturnService} (the original moves to RTO_INITIATED as a side effect). The exception is a
 * shipment that is itself a return child: it can't be returned again, so an exhausted return child
 * is held at the hub for ops disposition.</p>
 */
@Component
public class ExceptionsEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExceptionsEventsConsumer.class);
    private static final String SOURCE = "m11-exception-consumer";

    private final ShipmentStateMachine stateMachine;
    private final ShipmentRepository shipmentRepository;
    private final ReturnService returnService;

    ExceptionsEventsConsumer(ShipmentStateMachine stateMachine,
                             ShipmentRepository shipmentRepository,
                             ReturnService returnService) {
        this.stateMachine = stateMachine;
        this.shipmentRepository = shipmentRepository;
        this.returnService = returnService;
    }

    @RabbitListener(queues = OrdersMessagingTopology.EXCEPTIONS_QUEUE)
    public void onExceptionsEvent(ExceptionsEvent event) {
        TransitionContext ctx = TransitionContext.fromKafka(SOURCE, String.valueOf(event.shipmentId()));

        // RTO_INITIATED = "attempts exhausted, return to sender". Spawn a return child (or, if the
        // shipment is already a return child, hold it at the hub — no return-of-a-return).
        if (event.eventType() == ExceptionsEventType.RTO_INITIATED) {
            Shipment s = shipmentRepository.findById(event.shipmentId()).orElse(null);
            if (s == null) {
                log.warn("RTO_INITIATED for unknown shipment {} — ignored", event.shipmentId());
                return;
            }
            if (s.getReturnOfShipmentId() != null) {
                stateMachine.transition(event.shipmentId(), ShipmentState.HELD_AT_HUB, ctx);
            } else {
                returnService.initiateReturn(event.shipmentId(), ReturnReason.ATTEMPTS_EXHAUSTED, ctx);
            }
            return;
        }

        ShipmentState target = switch (event.eventType()) {
            case PICKUP_RESCHEDULED   -> ShipmentState.PICKUP_ASSIGNED;
            case DELIVERY_RESCHEDULED -> ShipmentState.DROP_ASSIGNED;
            // Reassign re-drives the M5 assignment trigger (the failed DELIVERY task is already terminal,
            // so dispatch assigns a fresh DA); reschedule above only flips M4 state (van-meeting redelivery).
            case DELIVERY_REASSIGNED  -> ShipmentState.HANDED_TO_DROP_VAN;
            // Legacy ops "complete RTO" click — the original normally completes via the child-delivered
            // signal now, but keep the manual path working.
            case RTO_COMPLETED        -> ShipmentState.RTO_COMPLETED;
            case RTO_INITIATED        -> null; // handled above
        };
        if (target == null) {
            return;
        }
        stateMachine.transition(event.shipmentId(), target, ctx);
    }
}
