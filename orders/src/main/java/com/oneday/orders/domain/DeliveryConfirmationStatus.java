package com.oneday.orders.domain;

/** Lifecycle of a receiver delivery confirmation. Silence stays PENDING → EXPIRED (silence = accept). */
public enum DeliveryConfirmationStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    EXPIRED
}
