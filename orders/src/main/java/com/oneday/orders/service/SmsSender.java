package com.oneday.orders.service;

/**
 * Sends a transactional SMS. The default {@code LoggingSmsSender} logs the message so notifications
 * work end-to-end before an SMS gateway lands; a real provider (MSG91) swaps in via
 * {@code notify.sms.provider=msg91}, mirroring the module's other ports (PayoutPort, PaymentPort).
 * Implementations must be best-effort — never throw into the caller.
 */
public interface SmsSender {
    void send(String phone, String message);
}
