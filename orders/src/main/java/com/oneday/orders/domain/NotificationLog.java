package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One outbox row = one rendered message to one recipient on one channel. The notification service
 * enqueues it PENDING; a scheduled drain delivers it (SENT) or records the failure (FAILED) and
 * retries until the attempt cap. Persisting first makes delivery observable and survivable across
 * restarts — the transactional-outbox pattern, mirroring {@code webhook_delivery}.
 */
@Entity
@Table(name = "notification_log")
@Getter
@Setter
@NoArgsConstructor
public class NotificationLog extends MutableBaseEntity {

    /** The event that triggered this (the NotificationEventType name), for reference/filtering. */
    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private NotificationChannel channel;

    /** Email address or E.164 phone, depending on channel. */
    @Column(name = "recipient", nullable = false, updatable = false)
    private String recipient;

    @Column(name = "subject", length = 300, updatable = false)
    private String subject;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "error")
    private String error;

    @Column(name = "sent_at")
    private Instant sentAt;
}
