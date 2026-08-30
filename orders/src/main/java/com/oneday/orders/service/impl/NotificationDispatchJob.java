package com.oneday.orders.service.impl;

import com.oneday.orders.config.NotifyProperties;
import com.oneday.orders.domain.NotificationChannel;
import com.oneday.orders.domain.NotificationLog;
import com.oneday.orders.domain.NotificationStatus;
import com.oneday.orders.repository.NotificationLogRepository;
import com.oneday.orders.service.EmailSender;
import com.oneday.orders.service.SmsSender;
import com.oneday.orders.service.WhatsAppSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Drains the notification outbox: picks up PENDING + retryable FAILED rows and delivers them via the
 * channel senders, recording the outcome per row. Retries a failed row on each sweep until the attempt
 * cap ({@code notify.max-attempts}), then leaves it FAILED for inspection.
 *
 * <p>Deliberately does NOT hold a DB transaction across the sender's HTTP call — each row is loaded,
 * sent, then saved in its own write. ponytail: single-instance-safe as-is (a bounded batch, idempotent
 * enough for pilot); add a SELECT … FOR UPDATE SKIP LOCKED claim if this ever runs multi-instance.
 */
@Component
class NotificationDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchJob.class);
    private static final int MAX_ERROR_LEN = 500;

    private final NotificationLogRepository repository;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final WhatsAppSender whatsAppSender;
    private final NotifyProperties props;

    NotificationDispatchJob(NotificationLogRepository repository, EmailSender emailSender,
                            SmsSender smsSender, WhatsAppSender whatsAppSender, NotifyProperties props) {
        this.repository = repository;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
        this.whatsAppSender = whatsAppSender;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${notify.dispatch-interval-ms:5000}")
    public void drain() {
        List<NotificationLog> batch = repository.findTop200ByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(
                List.of(NotificationStatus.PENDING, NotificationStatus.FAILED), props.getMaxAttempts());
        if (batch.isEmpty()) {
            return;
        }
        int sent = 0, failed = 0;
        for (NotificationLog row : batch) {
            row.setAttempts(row.getAttempts() + 1);
            try {
                switch (row.getChannel()) {
                    case EMAIL -> emailSender.send(row.getRecipient(), row.getSubject(), row.getBody());
                    case SMS -> smsSender.send(row.getRecipient(), row.getBody());
                    case WHATSAPP -> whatsAppSender.send(row.getRecipient(), row.getBody());
                }
                row.setStatus(NotificationStatus.SENT);
                row.setSentAt(Instant.now());
                row.setError(null);
                sent++;
            } catch (Exception e) {
                row.setStatus(NotificationStatus.FAILED);
                row.setError(truncate(e.getMessage()));
                failed++;
            }
            repository.save(row);   // own write per row — no tx held across the send
        }
        log.info("[notify] drained {} ({} sent, {} failed)", batch.size(), sent, failed);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "unknown error";
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }
}
