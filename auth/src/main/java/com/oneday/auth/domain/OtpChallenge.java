package com.oneday.auth.domain;

import com.oneday.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A short-lived, hashed, single-use phone OTP challenge. The cleartext code is never stored —
 * only its BCrypt hash. Each {@code requestOtp} replaces any prior challenge for the phone.
 */
@Getter
@Setter
@Entity
@Table(name = "auth_otp")
public class OtpChallenge extends BaseEntity {

    @Column(nullable = false, length = 15)
    private String phone;

    @Column(name = "otp_hash", nullable = false)
    private String otpHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private short attempts = 0;

    @Column(nullable = false)
    private boolean consumed = false;
}
