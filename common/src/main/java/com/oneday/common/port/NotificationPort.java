package com.oneday.common.port;

import com.oneday.common.port.dto.NotificationRequest;

/**
 * Implemented by the notification service.
 * M4 publishes to oneday.notifications.requested and returns immediately —
 * it does not block on or retry notification delivery.
 * The notification service owns channel selection, templating, and retry logic.
 */
public interface NotificationPort {
    void send(NotificationRequest request);
}
