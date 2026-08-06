package com.oneday.orders.dto;

import com.oneday.orders.domain.WebhookDelivery;

import java.time.Instant;
import java.util.UUID;

/** One webhook delivery attempt, for the developer console's delivery log. */
public record WebhookDeliveryResponse(
        UUID id,
        String event,
        String shipmentRef,
        String url,
        String status,
        Integer responseCode,
        int attempts,
        String error,
        Instant createdAt) {

    public static WebhookDeliveryResponse from(WebhookDelivery d) {
        return new WebhookDeliveryResponse(
                d.getId(), d.getEvent(), d.getShipmentRef(), d.getUrl(), d.getStatus().name(),
                d.getResponseCode(), d.getAttempts() == null ? 0 : d.getAttempts(),
                d.getError(), d.getCreatedAt());
    }
}
