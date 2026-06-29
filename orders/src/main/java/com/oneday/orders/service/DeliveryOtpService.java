package com.oneday.orders.service;

import java.util.UUID;

/**
 * Manages one-time passcodes (OTPs) for last-mile <em>delivery</em> verification — the drop-side
 * mirror of {@link PickupOtpService}.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>State machine enters {@code DROP_COLLECTED} (DA is out for delivery) → caller invokes
 *       {@link #generate}; the cleartext is sent to the recipient.</li>
 *   <li>At the door the recipient reads out the OTP; the DA enters it in their app.</li>
 *   <li>DA app calls {@code POST /internal/v1/shipments/{ref}/delivery-otp/verify} → {@link #verify}
 *       → on success the state machine transitions {@code DROP_COLLECTED → DROPPED}.</li>
 *   <li>If the recipient did not receive the OTP, DA calls {@code .../delivery-otp/resend}
 *       → {@link #resend} (capped).</li>
 * </ol>
 *
 * <h2>Security</h2>
 * Only the BCrypt hash is ever stored. The cleartext 4-digit OTP is returned once from
 * {@link #generate}/{@link #resend} and must be sent to the recipient immediately.
 */
public interface DeliveryOtpService {

    /**
     * Generates a fresh 4-digit delivery OTP for the given shipment and stores its BCrypt hash with a
     * configured expiry. Re-entrant: an existing row for the shipment is deleted first.
     *
     * @param shipmentId the shipment entering {@code DROP_COLLECTED}
     * @return the cleartext 4-digit OTP; send to the recipient immediately, do not persist
     */
    String generate(UUID shipmentId);

    /**
     * Verifies the OTP submitted by the DA app at delivery.
     *
     * @throws OtpVerificationException if the OTP is wrong, expired, or already used
     */
    void verify(UUID shipmentId, String otp);

    /**
     * Resends the OTP by generating a new one and invalidating the previous. Increments the resend
     * counter; blocks past the configured limit.
     *
     * @return the new cleartext OTP; send to the recipient immediately
     * @throws ResendLimitExceededException if the resend limit is reached
     */
    String resend(UUID shipmentId);

    // -------------------------------------------------------------------------
    // Exceptions
    // -------------------------------------------------------------------------

    /** Thrown when OTP verification fails for any reason (wrong, expired, used). */
    class OtpVerificationException extends RuntimeException {
        public OtpVerificationException(String message) { super(message); }
    }

    /** Thrown when the caller attempts a resend after the resend limit. */
    class ResendLimitExceededException extends RuntimeException {
        public ResendLimitExceededException(String message) { super(message); }
    }
}
