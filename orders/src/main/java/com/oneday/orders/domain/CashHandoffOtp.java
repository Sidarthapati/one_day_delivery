package com.oneday.orders.domain;

import com.oneday.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * BCrypt-hashed OTP proving the DA → station cash handoff (Discussion-3 ix). Mirrors {@code PickupOtp}:
 * exactly one active row per deposit (unique index on {@code deposit_id}), single-use, append-only
 * except {@code used}. The station cashier enters the code the DA presents to confirm receipt — so the
 * handoff is proven, not self-declared. Cleartext is never persisted.
 */
@Entity
@Table(name = "cash_handoff_otp")
@Getter
@Setter
@NoArgsConstructor
public class CashHandoffOtp extends BaseEntity {

    @Column(name = "deposit_id", nullable = false, updatable = false, unique = true)
    private UUID depositId;

    /** BCrypt hash of the 4-digit OTP (60 chars). */
    @Column(name = "otp_hash", length = 60, nullable = false, updatable = false)
    private String otpHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Set true on first successful verify; the only mutable field (prevents replay). */
    @Column(name = "used", nullable = false)
    private boolean used = false;
}
