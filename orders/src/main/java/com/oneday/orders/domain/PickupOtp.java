package com.oneday.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Stores the BCrypt-hashed OTP for DA pickup verification.
 *
 * <p>Exactly one row exists per shipment while an OTP is active. On resend the
 * old row is deleted and a new row is inserted — the unique index on
 * {@code shipment_id} enforces this at the DB level.</p>
 *
 * <p>This entity is append-only: once created, only {@code used} is ever updated
 * (set to {@code true} on successful verification). All other fields are
 * {@code updatable = false}.</p>
 */
@Entity
@Table(name = "pickup_otps")
public class PickupOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * The shipment this OTP is attached to.
     * UNIQUE index on the FK column enforces one active OTP per shipment.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false, updatable = false, unique = true)
    private Shipment shipment;

    /** BCrypt hash of the 4-digit OTP (60 chars). Cleartext is never persisted. */
    @Column(name = "otp_hash", length = 60, nullable = false, updatable = false)
    private String otpHash;

    /** Absolute timestamp after which this OTP is no longer valid (10 min from creation). */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /**
     * Set to {@code true} on first successful verification; prevents replay
     * of a valid OTP within the TTL window.
     * This is the only mutable field.
     */
    @Column(name = "used", nullable = false)
    private boolean used = false;

    /** How many times /resend has been called for this shipment. Capped at 3. */
    @Column(name = "resend_count", nullable = false, updatable = false)
    private short resendCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // -------------------------------------------------------------------------
    // Getters and setters
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }

    public Shipment getShipment() { return shipment; }
    public void setShipment(Shipment shipment) { this.shipment = shipment; }

    public String getOtpHash() { return otpHash; }
    public void setOtpHash(String otpHash) { this.otpHash = otpHash; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }

    public short getResendCount() { return resendCount; }
    public void setResendCount(short resendCount) { this.resendCount = resendCount; }

    public Instant getCreatedAt() { return createdAt; }
}
