package com.oneday.orders.events;

import com.oneday.orders.service.WebhookService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fires the merchant's outbound webhook on every B2B shipment state change. Runs AFTER_COMMIT (the
 * transition is durably recorded first) and the actual HTTP POST is handed to a background executor
 * inside {@link WebhookService}, so a slow or dead endpoint never blocks or rolls back the booking.
 */
@Component
class WebhookDispatcher {

    private final WebhookService webhooks;

    WebhookDispatcher(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransition(ShipmentTransitioned event) {
        webhooks.dispatchForTransition(event);
    }
}
