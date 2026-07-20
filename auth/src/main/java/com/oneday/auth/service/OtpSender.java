package com.oneday.auth.service;

/**
 * Delivers an OTP code to a phone. The default {@code LoggingOtpSender} logs it (so the flow works
 * on dev/staging before an SMS provider exists); a real SMS-backed sender swaps in later via
 * {@code @Primary}, mirroring the module's other ports.
 */
public interface OtpSender {
    void send(String phone, String code);
}
