package com.oneday.orders.service;

import java.util.Optional;
import java.util.UUID;

/**
 * Manages one-time passcodes (OTPs) for DA pickup verification.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>State machine enters {@code PICKUP_ASSIGNED} → caller invokes {@link #generate}.</li>
 *   <li>DA presents the OTP to the sender; sender confirms on the DA app.</li>
 *   <li>DA app calls {@code POST /internal/v1/shipments/{ref}/pickup-otp/verify}
 *       → {@link #verify} is called → on success, state machine transitions
 *       {@code PICKUP_ASSIGNED → PICKED_UP}.</li>
 *   <li>If the sender did not receive the OTP, DA calls
 *       {@code POST /internal/v1/shipments/{ref}/pickup-otp/resend}
 *       → {@link #resend} is called (max 3 times).</li>
 * </ol>
 *
 * <h2>Security</h2>
 * Only the BCrypt hash of the OTP is ever stored in the DB. The cleartext
 * 4-digit OTP is returned once from {@link #generate} and must be sent to
 * the sender via the notification service immediately.
 */
public interface PickupOtpService {

    /**
     * Generates a fresh 4-digit OTP for the given shipment and stores its
     * BCrypt hash in {@code pickup_otps} with a 10-minute expiry.
     *
     * <p>If an OTP row already exists for this shipment (e.g. called twice due
     * to a state machine retry), the old row is deleted and a new one inserted.
     *
     * @param shipmentId the shipment entering {@code PICKUP_ASSIGNED}
     * @return the cleartext 4-digit OTP string (e.g. {@code "4821"}); send to
     *         sender immediately and do not persist
     */
    String generate(UUID shipmentId);

    /**
     * Verifies the OTP submitted by the DA app.
     *
     * @param shipmentId the shipment being picked up
     * @param otp        the 4-digit OTP entered by the DA
     * @throws OtpVerificationException if the OTP is wrong, expired, or already used
     */
    void verify(UUID shipmentId, String otp);

    /**
     * Resends the OTP by generating a new one and invalidating the previous.
     * Increments the resend counter. After the third resend the shipment is
     * flagged and further resends are blocked.
     *
     * @param shipmentId the shipment for which to resend
     * @return the new cleartext OTP string; send to sender immediately
     * @throws ResendLimitExceededException if {@code resend_count >= 3}
     */
    String resend(UUID shipmentId);

    /**
     * Owner-facing peek: returns the current cleartext OTP for the shipment, but only if {@code userId}
     * booked it. Reads the in-memory cache (populated at generate/resend), so it's empty if none has been
     * generated yet or the cache was cleared by a restart.
     *
     * @return the cleartext OTP, or empty if not found / not owned / not cached
     */
    Optional<String> peekForOwner(String userId, String shipmentRef);

    /**
     * Owner-facing regenerate: mints a fresh OTP for the shipment (resetting its TTL) and returns the
     * cleartext, only if {@code userId} booked it. No resend cap (uses {@link #generate}).
     *
     * @return the fresh cleartext OTP, or empty if the shipment isn't found / not owned
     * @throws IllegalStateException if the shipment is not awaiting pickup ({@code PICKUP_ASSIGNED})
     */
    Optional<String> regenerateForOwner(String userId, String shipmentRef);

    // -------------------------------------------------------------------------
    // Exceptions
    // -------------------------------------------------------------------------

    /** Thrown when OTP verification fails for any reason (wrong, expired, used). */
    class OtpVerificationException extends RuntimeException {
        public OtpVerificationException(String message) { super(message); }
    }

    /** Thrown when the caller attempts a resend after the 3-resend limit. */
    class ResendLimitExceededException extends RuntimeException {
        public ResendLimitExceededException(String message) { super(message); }
    }
}
