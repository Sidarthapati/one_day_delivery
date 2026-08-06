package com.oneday.orders.service.impl;

import com.oneday.orders.service.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default email sender — no provider. Logs the message so notifications work before an email
 * provider lands. A real provider replaces this via {@code notify.email.provider=sendgrid}.
 */
@Component
@ConditionalOnProperty(name = "notify.email.provider", havingValue = "log", matchIfMissing = true)
class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    LoggingEmailSender() {
        log.info("[notify] email provider=log — messages are logged, not sent (dev/staging only)");
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        log.warn("[notify:email] → {} | {} | {}", toEmail, subject, body);
    }
}
