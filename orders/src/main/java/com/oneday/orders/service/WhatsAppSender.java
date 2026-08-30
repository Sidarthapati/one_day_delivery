package com.oneday.orders.service;

/**
 * Sends a WhatsApp message. The default {@code LoggingWhatsAppSender} logs it so notifications work
 * end-to-end before a BSP/Meta account lands; a real provider swaps in via
 * {@code notify.whatsapp.provider=meta}, mirroring {@link SmsSender} / {@link EmailSender}.
 * Implementations must be best-effort — never throw into the caller of the notification port.
 */
public interface WhatsAppSender {
    void send(String phone, String message);
}
