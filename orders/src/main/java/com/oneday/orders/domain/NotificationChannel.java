package com.oneday.orders.domain;

/** Delivery channel for a notification. (WHATSAPP is 8 chars — fits notification_log.channel VARCHAR(8).) */
public enum NotificationChannel {
    EMAIL,
    SMS,
    WHATSAPP
}
