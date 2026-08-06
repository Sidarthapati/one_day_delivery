package com.oneday.orders.service.impl;

import com.oneday.orders.service.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default SMS sender — no provider. Logs the message so notifications work end-to-end on dev/staging
 * (read them from the app log). A real provider replaces this via {@code notify.sms.provider=msg91}.
 * Logged at WARN precisely because it must NOT be the sender in production.
 */
@Component
@ConditionalOnProperty(name = "notify.sms.provider", havingValue = "log", matchIfMissing = true)
class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    LoggingSmsSender() {
        log.info("[notify] sms provider=log — messages are logged, not sent (dev/staging only)");
    }

    @Override
    public void send(String phone, String message) {
        log.warn("[notify:sms] → {} : {}", phone, message);
    }
}
