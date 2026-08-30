package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A receiver's accept/reject prompt for one delivery (see {@code V4_45}). Born PENDING when the parcel's
 * flight departs (or, same-city, it's sorted for delivery); the receiver opens the no-login link and
 * accepts or rejects. Silence stays PENDING and the expiry sweep flips it EXPIRED — silence = accept.
 * Only the SHA-256 hash of the opaque link token is stored; the cleartext lives only in the emailed link.
 */
@Entity
@Table(name = "delivery_confirmation")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryConfirmation extends MutableBaseEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    /** The delivery attempt this prompt belongs to (M11 attempt_no at prompt time). */
    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryConfirmationStatus status = DeliveryConfirmationStatus.PENDING;

    /** Computed expected delivery instant shown to the receiver. */
    @Column(name = "eta")
    private Instant eta;

    /** The shift the ETA resolved to (SHIFT_1 | SHIFT_2). */
    @Column(name = "eta_shift", length = 20, updatable = false)
    private String etaShift;

    /** Human framing of the ETA day: TODAY | NEXT_DAY. */
    @Column(name = "eta_day", length = 10, updatable = false)
    private String etaDay;

    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private String channel = "EMAIL";

    /** The next-day shift the receiver picked on reject (SHIFT_1 | SHIFT_2). */
    @Column(name = "response_shift", length = 20)
    private String responseShift;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;
}
