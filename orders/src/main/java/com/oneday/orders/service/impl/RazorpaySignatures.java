package com.oneday.orders.service.impl;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Razorpay's payment signature scheme: {@code HMAC_SHA256(order_id + "|" + payment_id, key_secret)},
 * hex-encoded. Identical in mock and live mode — the only difference is who computes it
 * (our mock checkout vs. Razorpay's servers). Verification here is therefore real either way.
 */
public final class RazorpaySignatures {

    private RazorpaySignatures() {}

    public static String sign(String orderId, String paymentId, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute Razorpay signature", e);
        }
    }

    /** Constant-time compare to avoid timing side-channels on signature checks. */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
