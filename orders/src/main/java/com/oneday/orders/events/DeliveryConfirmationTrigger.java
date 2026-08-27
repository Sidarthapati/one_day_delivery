package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.service.DeliveryConfirmationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.Set;

/**
 * Fires the receiver accept/reject prompt off the shipment state machine (in-process
 * {@link ShipmentTransitioned}). Intercity: on {@code DEPARTED} — the assigned flight leaving the origin
 * gives the receiver hours of lead time. Same-city (no flight): on {@code DEST_HUB_PROCESSING} — the
 * parcel sorted for delivery at the single hub. The prompt is idempotent per shipment, so an intercity
 * parcel later reaching {@code DEST_HUB_PROCESSING} is a no-op. Runs AFTER_COMMIT so the transit state is
 * durable before the receiver is told, and never blocks the transition.
 */
@Component
class DeliveryConfirmationTrigger {

    private static final Set<ShipmentState> PROMPT_STATES =
            EnumSet.of(ShipmentState.DEPARTED, ShipmentState.DEST_HUB_PROCESSING);

    private final DeliveryConfirmationService confirmationService;

    DeliveryConfirmationTrigger(DeliveryConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransition(ShipmentTransitioned e) {
        if (PROMPT_STATES.contains(e.toState())) {
            confirmationService.promptOnDeparture(e.shipmentId());
        }
    }
}
