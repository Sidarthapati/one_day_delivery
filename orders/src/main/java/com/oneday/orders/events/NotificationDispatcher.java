package com.oneday.orders.events;

import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.Notifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends customer notifications on shipment milestones. Runs AFTER_COMMIT (the transition is durably
 * recorded first); the {@link Notifier} delivers asynchronously and best-effort, so notification
 * delivery never blocks or rolls back the transition. Applies to every customer type (B2C/C2C/B2B) —
 * the {@link Notifier} decides which states notify and who receives them.
 */
@Component
class NotificationDispatcher {

    private final ShipmentRepository shipments;
    private final Notifier notifier;

    NotificationDispatcher(ShipmentRepository shipments, Notifier notifier) {
        this.shipments = shipments;
        this.notifier = notifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransition(ShipmentTransitioned event) {
        Shipment shipment = shipments.findById(event.shipmentId()).orElse(null);
        if (shipment != null) {
            notifier.shipmentMilestone(shipment, event.toState());
        }
    }
}
