package com.oneday.orders.service;

import java.util.UUID;

/**
 * OTP proving the DA → station cash handoff (Discussion-3 ix). The DA gets the cleartext code when they
 * record a deposit; the station cashier enters it to confirm receipt — so the handoff is verified, not
 * self-declared. Mirrors {@code PickupOtpService} (BCrypt, single-use, pessimistic-lock verify).
 */
public interface CashHandoffOtpService {

    /** Generate (or replace) the handoff OTP for a deposit; returns the cleartext code (never stored). */
    String generate(UUID depositId);

    /** Verify the code for a deposit and burn it (single-use). Throws {@link OtpVerificationException} on failure. */
    void verify(UUID depositId, String otp);

    /** Thrown when no active OTP exists, or it is used / expired / incorrect → 422. */
    class OtpVerificationException extends RuntimeException {
        public OtpVerificationException(String message) { super(message); }
    }
}
