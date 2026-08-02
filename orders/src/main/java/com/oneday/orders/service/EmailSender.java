package com.oneday.orders.service;

/**
 * Sends a transactional email. The default {@code LoggingEmailSender} logs it so notifications work
 * before an email provider lands; a real provider (SendGrid) swaps in via
 * {@code notify.email.provider=sendgrid}. Implementations must be best-effort — never throw.
 */
public interface EmailSender {
    void send(String toEmail, String subject, String body);
}
