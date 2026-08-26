package com.oneday.orders.domain;

/** Outbox lifecycle: PENDING (enqueued) → SENT (delivered) or FAILED (retryable until the attempt cap). */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}
