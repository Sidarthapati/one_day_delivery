package com.oneday.orders.service.impl;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Webhook payload signature: {@code HMAC_SHA256(rawBody, webhook_secret)}, hex-encoded, sent in the
 * {@code X-Godspeed-Signature} header. The merchant recomputes it over the received body to verify
 * the call came from us and wasn't tampered with — the same scheme Stripe/Razorpay webhooks use.
 */
final class WebhookSignatures {

    private WebhookSignatures() {}

    static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute webhook signature", e);
        }
    }
}
