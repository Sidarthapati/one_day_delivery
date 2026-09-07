package com.oneday.orders.service;

/**
 * Handles the inbound bank-credit webhook (Discussion-3 ix): verifies the provider's HMAC signature and,
 * on success, confirms the deposit's cash landed in the company account (BANK_CONFIRMED), which settles
 * that DA's collections FIFO. Disabled until a signing secret is configured (go-live issue).
 */
public interface CodBankWebhookService {

    /** Verify the signature over {@code rawBody} and confirm the referenced deposit's bank credit. */
    void confirmFromWebhook(String rawBody, String signature);
}
