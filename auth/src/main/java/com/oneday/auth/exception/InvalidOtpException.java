package com.oneday.auth.exception;

/** The OTP is missing, expired, exhausted, or incorrect. Message is safe to surface to the user. */
public class InvalidOtpException extends RuntimeException {
    public InvalidOtpException(String message) {
        super(message);
    }
}
