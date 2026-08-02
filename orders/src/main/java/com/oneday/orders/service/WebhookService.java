package com.oneday.orders.service;

import com.oneday.orders.dto.WebhookConfigResponse;
import com.oneday.orders.dto.WebhookDeliveryResponse;
import com.oneday.orders.events.ShipmentTransitioned;

import java.util.List;
import java.util.UUID;

/**
 * Outbound webhooks for the B2B developer surface. A merchant registers a URL + secret on their
 * account; we POST a signed JSON payload on each of their shipments' state changes.
 */
public interface WebhookService {

    /** Fire-and-forget: POST the shipment's new state to the account's webhook (if configured). */
    void dispatchForTransition(ShipmentTransitioned event);

    /** The account's webhook configuration (url + secret). */
    WebhookConfigResponse config(UUID accountId);

    /** Set/replace the webhook URL; optionally rotate the signing secret (a first URL mints one). */
    WebhookConfigResponse updateConfig(UUID accountId, String url, boolean regenerateSecret);

    /** Send a sample {@code webhook.test} event to the configured URL, synchronously. */
    WebhookDeliveryResponse sendTest(UUID accountId);

    /** Recent delivery attempts for the account, newest first. */
    List<WebhookDeliveryResponse> recentDeliveries(UUID accountId, int limit);
}
