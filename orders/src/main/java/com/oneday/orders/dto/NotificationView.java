package com.oneday.orders.dto;

import com.oneday.orders.domain.NotificationChannel;
import com.oneday.orders.domain.NotificationLog;

import java.time.Instant;
import java.util.UUID;

/** One notification for the in-app bell. Snake_case on the wire (project-wide Jackson strategy). */
public record NotificationView(
        UUID id,
        String eventType,
        NotificationChannel channel,
        String subject,
        String body,
        Instant createdAt) {

    public static NotificationView from(NotificationLog n) {
        return new NotificationView(n.getId(), n.getEventType(), n.getChannel(),
                n.getSubject(), n.getBody(), n.getCreatedAt());
    }
}
