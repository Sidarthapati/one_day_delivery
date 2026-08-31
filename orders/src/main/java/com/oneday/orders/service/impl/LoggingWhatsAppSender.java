package com.oneday.orders.service.impl;

import com.oneday.orders.service.WhatsAppSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default WhatsApp sender — no provider. Logs the message so notifications work end-to-end on
 * dev/staging (read them from the app log). A real provider replaces this via
 * {@code notify.whatsapp.provider=meta}. Logged at WARN precisely because it must NOT be the sender
 * in production.
 */
@Component
@ConditionalOnProperty(name = "notify.whatsapp.provider", havingValue = "log", matchIfMissing = true)
class LoggingWhatsAppSender implements WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingWhatsAppSender.class);

    LoggingWhatsAppSender() {
        log.info("[notify] whatsapp provider=log — messages are logged, not sent (dev/staging only)");
    }

    @Override
    public void send(String phone, String message) {
        log.warn("[notify:whatsapp] → {} : {}", phone, message);
    }
}
