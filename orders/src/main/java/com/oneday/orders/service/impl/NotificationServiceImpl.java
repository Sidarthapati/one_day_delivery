package com.oneday.orders.service.impl;

import com.oneday.common.port.NotificationPort;
import com.oneday.common.port.dto.NotificationRequest;
import com.oneday.orders.domain.NotificationChannel;
import com.oneday.orders.domain.NotificationLog;
import com.oneday.orders.domain.NotificationStatus;
import com.oneday.orders.repository.NotificationLogRepository;
import com.oneday.orders.service.NotificationTemplates;
import com.oneday.orders.service.NotificationTemplates.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The notification foundation. {@code send} renders the event's template and ENQUEUES an outbox row
 * per (deliverable) channel — a fast, transactional insert that commits with the caller's work, so a
 * rolled-back business action never notifies. The actual delivery + retry is the
 * {@link NotificationDispatchJob} draining the outbox.
 *
 * <p>Generic seam for the whole platform (cross-module callers reach it via {@link NotificationPort}).
 * The older {@code Notifier} (shipment milestones / remittance) is a separate, still-live path; folding
 * it through this outbox is a follow-up — see {@code ponytail} note.
 */
@Service
class NotificationServiceImpl implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationLogRepository repository;

    NotificationServiceImpl(NotificationLogRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void send(NotificationRequest request) {
        Template template = NotificationTemplates.forType(request.type());
        if (template == null) {
            log.warn("[notify] no template for event {} — dropping", request.type());
            return;
        }
        String subject = NotificationTemplates.render(template.subject(), request.params());
        String body = NotificationTemplates.render(template.body(), request.params());

        List<NotificationLog> rows = new ArrayList<>(2);
        for (NotificationChannel channel : template.channels()) {
            String recipient = channel == NotificationChannel.EMAIL
                    ? request.recipientEmail() : request.recipientPhone();
            if (recipient == null || recipient.isBlank()) {
                continue;   // template wants this channel but the recipient can't receive it
            }
            NotificationLog row = new NotificationLog();
            row.setEventType(request.type().name());
            row.setChannel(channel);
            row.setRecipient(recipient.trim());
            row.setSubject(channel == NotificationChannel.EMAIL ? subject : null);
            row.setBody(body);
            row.setStatus(NotificationStatus.PENDING);
            rows.add(row);
        }
        if (rows.isEmpty()) {
            log.warn("[notify] {} had no deliverable channel (no email/phone) — dropping", request.type());
            return;
        }
        repository.saveAll(rows);
    }
}
