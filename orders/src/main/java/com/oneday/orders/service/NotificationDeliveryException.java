package com.oneday.orders.service;

/**
 * A channel sender failed to deliver a notification. Lets the outbox drain mark the row FAILED and
 * retry; best-effort callers (the legacy {@code Notifier}) simply swallow it.
 */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
