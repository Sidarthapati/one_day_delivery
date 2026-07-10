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
 * Stores the BCrypt-hashed OTP for last-mile <em>delivery</em> verification — the mirror of
 * {@link PickupOtp} on the drop side.
 *
 * <p>Flow: when a shipment enters {@code DROP_COLLECTED} (the DA has the parcel and is out for
 * delivery), {@code DeliveryOtpService} generates a 4-digit OTP, stores only the BCrypt hash here,
 * and the cleartext is sent to the recipient. At the door the recipient reads it out; the DA enters
 * it in their app; on success the state machine transitions {@code DROP_COLLECTED → DROPPED}.</p>
 *
 * <p>Exactly one active row per shipment (unique index on {@code shipment_id}); on resend the old
 * row is deleted and a fresh row inserted. Append-only: once created only {@code used} is mutated.</p>
 */
@Entity
@Table(name = "delivery_otps")
public class DeliveryOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The shipment this OTP is attached to. UNIQUE index enforces one active OTP per shipment. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false, updatable = false, unique = true)
    private Shipment shipment;

    /** BCrypt hash of the 4-digit OTP (60 chars). Cleartext is never persisted. */
    @Column(name = "otp_hash", length = 60, nullable = false, updatable = false)
    private String otpHash;

    /** Absolute timestamp after which this OTP is no longer valid. */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Set to {@code true} on first successful verification; prevents replay within the TTL window. */
    @Column(name = "used", nullable = false)
    private boolean used = false;

    /** How many times /resend has been called for this shipment. Capped by application logic. */
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
