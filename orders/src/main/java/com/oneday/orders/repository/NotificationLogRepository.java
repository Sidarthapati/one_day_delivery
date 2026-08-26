package com.oneday.orders.repository;

import com.oneday.orders.domain.NotificationLog;
import com.oneday.orders.domain.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    /**
     * The drain batch: undelivered rows still under the retry cap, oldest first. Bounded to avoid a
     * runaway sweep. Backed by {@code idx_notification_log_undelivered}.
     */
    List<NotificationLog> findTop200ByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(
            Collection<NotificationStatus> statuses, int maxAttempts);

    /**
     * The recent notifications addressed to any of these recipients (a merchant's billing email /
     * support phone), newest first — backs the in-app notifications bell.
     * ponytail: scoped by recipient string (no account_id on the row yet); add an account_id column
     * if a merchant ever changes their billing email and needs history to follow.
     */
    List<NotificationLog> findTop50ByRecipientInOrderByCreatedAtDesc(Collection<String> recipients);
}
