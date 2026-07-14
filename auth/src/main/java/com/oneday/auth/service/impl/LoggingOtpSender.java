package com.oneday.auth.service.impl;

import com.oneday.auth.service.OtpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default OTP sender: logs the code so the phone-OTP flow works end-to-end before a real SMS
 * gateway lands (read the code from the app logs on dev/staging). A real SMS sender replaces this
 * via {@code @Primary}. Logged at WARN precisely because it must NOT be the sender in production.
 */
@Component
class LoggingOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    @Override
    public void send(String phone, String code) {
        log.warn("[otp] no SMS provider wired — code for {} is {} (dev/staging only)", phone, code);
    }
}
