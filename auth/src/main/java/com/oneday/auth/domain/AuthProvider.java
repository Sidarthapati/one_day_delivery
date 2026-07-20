package com.oneday.auth.domain;

/** How a user account authenticates. Local users have a password; social/OTP users do not. */
public enum AuthProvider {
    LOCAL,
    GOOGLE,
    PHONE_OTP
}
