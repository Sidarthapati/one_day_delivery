package com.oneday.orders.service;

import com.oneday.orders.dto.CancellationResponse;

/**
 * M4 cancellation flow (PR #13). Cancels a shipment if its state still permits it
 * ({@link CancellationPolicy}), reverses payment (PREPAID Razorpay refund) or credit
 * (B2B outstanding-balance decrement), transitions it to {@code CANCELLED}, and emits the rich
 * {@code ShipmentCancelledEvent} so M5 can drop it from the DA queue and M10 can close SLA tracking.
 */
public interface CancellationService {

    /**
     * Cancels the shipment identified by {@code shipmentRef}.
     *
     * @param shipmentRef the shipment reference (e.g. {@code 1DD-BLR-20260601-00001})
     * @param reason      customer- or ops-supplied cancellation reason (nullable)
     * @param userId      the authenticated caller (becomes the transition actor; for B2B must own
     *                    the shipment's account)
     * @param b2bLane     {@code true} when called from the B2B endpoint — guards that the shipment
     *                    belongs to the matching lane so a B2C caller cannot cancel a B2B shipment
     *                    (or vice-versa)
     * @return the cancellation outcome (state + optional refund block)
     * @throws jakarta.persistence.EntityNotFoundException if no such shipment in this lane (404)
     * @throws CancellationNotAllowedException             if the state is past the cutoff (409)
     * @throws B2bBookingService.AccountAccessException    if a B2B caller does not own the account (403)
     */
    CancellationResponse cancel(String shipmentRef, String reason, String userId, boolean b2bLane);

    /** Thrown when the shipment's current state is past the cancellation cutoff (HTTP 409). */
    class CancellationNotAllowedException extends RuntimeException {
        public CancellationNotAllowedException(String message) { super(message); }
    }
}
