package com.oneday.orders.events;

import com.oneday.common.domain.MeetingMode;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.common.port.CityMeetingModePort;
import com.oneday.orders.service.PickupOtpService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M5 DA events from the {@code oneday.da.events} exchange (queue {@code orders.da}) and
 * drives the M4 state machine.
 *
 * <p>Until M5 produces, the queue simply stays empty.</p>
 *
 * <p><b>Pickup-OTP side-effect:</b> when a shipment enters {@code PICKUP_ASSIGNED}, a fresh
 * pickup OTP is generated (BCrypt-hashed, 10-min expiry) so the DA can verify it with the sender.
 * The cleartext OTP returned by {@link PickupOtpService#generate} must be delivered to the sender
 * via the notification service — until that service exists (separate {@code oneday.notifications.events}
 * fan-out) the OTP is logged at DEBUG only. Generation runs <em>after</em> the transition commits,
 * so the shipment is firmly in {@code PICKUP_ASSIGNED} before an OTP is minted.</p>
 */
@Component
public class DaEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(DaEventsConsumer.class);
    private static final String SOURCE = "m5-da-consumer";

    private final ShipmentStateMachine stateMachine;
    private final PickupOtpService pickupOtpService;
    private final CityMeetingModePort meetingModePort;

    DaEventsConsumer(ShipmentStateMachine stateMachine, PickupOtpService pickupOtpService,
                     CityMeetingModePort meetingModePort) {
        this.stateMachine = stateMachine;
        this.pickupOtpService = pickupOtpService;
        this.meetingModePort = meetingModePort;
    }

    @RabbitListener(queues = OrdersMessagingTopology.DA_QUEUE)
    public void onDaEvent(DaLifecycleEvent event) {
        // The delivery-side events (DROP_ASSIGNED / DROP_COLLECTED) are shared between VAN_MEETING and
        // HUB_RETURN cities — only the delivery city's mode distinguishes them. Resolve it once so the
        // parcel's M4 state honestly reflects "on a drop van" vs "collected at the hub". Null/unknown → van.
        boolean hubReturn = event.cityId() != null
                && meetingModePort.modeFor(event.cityId()) == MeetingMode.HUB_RETURN;
        ShipmentState target = switch (event.eventType()) {
            case PICKUP_ASSIGNED       -> ShipmentState.PICKUP_ASSIGNED;
            case PICKUP_FAILED         -> ShipmentState.PICKUP_FAILED;
            case VAN_HANDOFF_COMPLETED -> ShipmentState.HANDED_TO_PICKUP_VAN;
            // HUB_RETURN city: DA carried the pickup back to the hub itself — no pickup van involved.
            case HUB_RETURN_HANDOFF_COMPLETED -> ShipmentState.RETURNED_TO_HUB;
            case DROP_ASSIGNED         -> hubReturn ? ShipmentState.HUB_DELIVERY_ASSIGNED : ShipmentState.DROP_ASSIGNED;
            case DROP_COLLECTED        -> hubReturn ? ShipmentState.COLLECTED_FROM_HUB    : ShipmentState.DROP_COLLECTED;
            case DROP_COMPLETED        -> ShipmentState.DROPPED;
            case DROP_FAILED           -> ShipmentState.DELIVERY_FAILED;
            // Not consumed by M4 — PICKED_UP is driven exclusively by the OTP verify endpoint.
            case PICKUP_COMPLETED      -> null;
            // M5-internal events (QUEUE_REORDERED, DA_ABSENT, CRON_MISSED, COD_COLLECTED,
            // TASK_DEFERRED_SHIFT_ENDED) drive no M4 transition — ignore them. A default keeps M4
            // compiling as M5 grows DaEventType (the enum is owned by M5/dispatch).
            default                    -> null;
        };
        if (target == null) {
            log.debug("DA event {} ignored for shipment {}", event.eventType(), event.shipmentId());
            return;
        }
        // Idempotent + unprocessable-tolerant: the demo fast-forwards these states by hand (an event can
        // arrive after the shipment already advanced → IllegalStateTransition) and can delete a shipment
        // out from under an in-flight event (demo "Clear bookings" → EntityNotFound). Both are terminal
        // for this message — ack and move on, never retry/DLQ.
        try {
            stateMachine.transition(event.shipmentId(), target,
                    TransitionContext.fromKafka(SOURCE, String.valueOf(event.shipmentId())));
        } catch (IllegalStateTransitionException | EntityNotFoundException e) {
            log.debug("DA event {} → {} skipped for {} ({})",
                    event.eventType(), target, event.shipmentId(), e.getMessage());
            return;
        }

        if (target == ShipmentState.PICKUP_ASSIGNED) {
            generatePickupOtp(event);   // only on a real BOOKED→PICKUP_ASSIGNED, never a redelivery
        }
    }

    /** Mint a pickup OTP for the sender. Best-effort: a failure here never undoes the transition. */
    private void generatePickupOtp(DaLifecycleEvent event) {
        try {
            String otp = pickupOtpService.generate(event.shipmentId());
            // TODO: dispatch to sender via the notification service (oneday.notifications.events).
            log.debug("Generated pickup OTP for shipment {} (cleartext suppressed in prod): {}",
                    event.shipmentId(), otp);
        } catch (RuntimeException e) {
            log.error("Failed to generate pickup OTP for shipment {}: {}",
                    event.shipmentId(), e.getMessage());
        }
    }
}
