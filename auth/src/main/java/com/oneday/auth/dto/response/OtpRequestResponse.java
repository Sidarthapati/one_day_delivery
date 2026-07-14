package com.oneday.auth.dto.response;

/**
 * Returned by /auth/otp/request. Deliberately identical whether or not the phone maps to an
 * existing user — it never reveals account existence. Only the delivery + TTL are exposed.
 */
public record OtpRequestResponse(
        boolean sent,
        long expiresInSeconds
) {}
