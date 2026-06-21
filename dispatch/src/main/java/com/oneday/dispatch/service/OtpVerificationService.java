package com.oneday.dispatch.service;

import java.util.UUID;

/**
 * Pickup-OTP verification for the DA app (design §6.2). M5 owns the DA-facing surface and drives M4's
 * pickup OTP <b>in-process</b> (both {@code PickupOtpService} and {@code ShipmentStateMachine} are
 * public M4 services keyed by shipmentId — no cross-service HTTP/token needed in the monolith).
 *
 * <p>On a correct OTP M4 verifies it and the shipment transitions to {@code PICKED_UP}; M5 then emits
 * {@code PICKUP_COMPLETED} (M10's SLA-leg trigger). The dispatch task itself stays IN_PROGRESS until
 * the later van handoff.</p>
 */
public interface OtpVerificationService {

    /** Verify the sender's OTP for the task's shipment and transition it to PICKED_UP. */
    void verifyOtp(UUID daId, UUID taskId, String otp);

    /** Reissue the pickup OTP for the task's shipment (max 3 per M4). */
    void resendOtp(UUID daId, UUID taskId);
}
