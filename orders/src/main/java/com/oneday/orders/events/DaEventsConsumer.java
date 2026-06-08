package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.KafkaTopics;
import com.oneday.common.kafka.events.DaEvent;
import com.oneday.orders.service.PickupOtpService;
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
 * {@code orders.kafka.consumer.auto-startup=true} to enable.</p>
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

    DaEventsConsumer(ShipmentStateMachine stateMachine, PickupOtpService pickupOtpService) {
        this.stateMachine = stateMachine;
        this.pickupOtpService = pickupOtpService;
    }

    @KafkaListener(topics = KafkaTopics.DA_EVENTS, groupId = "orders-service",
            autoStartup = "${orders.kafka.consumer.auto-startup:false}")
    public void onDaEvent(DaEvent event) {
        ShipmentState target = switch (event.eventType()) {
            case PICKUP_ASSIGNED       -> ShipmentState.PICKUP_ASSIGNED;
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

        if (target == ShipmentState.PICKUP_ASSIGNED) {
            generatePickupOtp(event);
        }
    }

    /** Mint a pickup OTP for the sender. Best-effort: a failure here never undoes the transition. */
    private void generatePickupOtp(DaEvent event) {
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
