package com.oneday.orders.domain;

/** Outcome of an outbound webhook POST. */
public enum WebhookDeliveryStatus {
    PENDING,
    DELIVERED,
    FAILED
}
