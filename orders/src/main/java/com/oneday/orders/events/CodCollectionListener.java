package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.service.CodRemittanceService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Advances a shipment's COD collection off the state machine. Runs AFTER_COMMIT so the collection
 * is only touched once the delivery/cancellation is durably recorded. The service methods open
 * their own (REQUIRES_NEW) transaction. No-op for non-COD shipments (no collection row exists).
 */
@Component
class CodCollectionListener {

    private final CodRemittanceService cod;

    CodCollectionListener(CodRemittanceService cod) {
        this.cod = cod;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransition(ShipmentTransitioned event) {
        switch (event.toState()) {
            // Buyer paid on delivery (DA doorstep drop or hub self-collect). The transition actor is
            // the DA who took the cash (doorstep) or the hub operator (self-collect).
            case DROPPED, HUB_COLLECTED -> cod.onDelivered(event.shipmentId(), parseActor(event.triggeredBy()));
            // Never reached the buyer — nothing collected.
            case CANCELLED, RTO_COMPLETED -> cod.onCancelled(event.shipmentId());
            default -> { /* no COD effect */ }
        }
    }

    /** The actor id is a user UUID for real transitions; null for system/unparseable actors. */
    private static UUID parseActor(String triggeredBy) {
        if (triggeredBy == null || triggeredBy.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(triggeredBy.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
