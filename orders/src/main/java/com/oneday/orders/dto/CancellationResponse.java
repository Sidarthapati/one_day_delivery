package com.oneday.orders.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oneday.common.domain.enums.ShipmentState;

/**
 * Response for {@code DELETE /api/v1/{b2c|b2b}/shipments/{ref}}.
 *
 * <p>The {@code disposition} says what the cancel actually did: a not-yet-in-custody shipment is
 * {@code CANCELLED} (with an optional refund block); an in-custody shipment is turned into an RTO
 * instead — {@code RETURN_INITIATED} when the return child was spawned right away (a
 * {@code returnChildRef} is returned) or {@code RETURN_SCHEDULED} when the parcel is still in transit
 * and the return fires when it next reaches a hub.</p>
 *
 * <p>The {@code refund} block is present only for a PREPAID retail cancellation that initiated a
 * Razorpay refund. COD and B2B (credit reversal) cancellations omit it ({@code NON_NULL}).
 * Field names serialise to snake_case via the global Jackson config.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CancellationResponse(
        String shipmentRef,
        ShipmentState state,
        RefundSummary refund,
        Disposition disposition,
        String returnChildRef) {

    /** What a cancel request resolved to. */
    public enum Disposition {
        /** Not yet in custody — cancelled (optionally refunded). */
        CANCELLED,
        /** In custody — a return child was spawned immediately (see {@code returnChildRef}). */
        RETURN_INITIATED,
        /** In custody, still in transit — the return will fire when the parcel next reaches a hub. */
        RETURN_SCHEDULED
    }

    /** Back-compat: a plain cancellation (disposition {@code CANCELLED}, no return child). */
    public CancellationResponse(String shipmentRef, ShipmentState state, RefundSummary refund) {
        this(shipmentRef, state, refund, Disposition.CANCELLED, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefundSummary(
            String status,            // REFUND_INITIATED
            Integer estimatedDays,    // typical Razorpay settlement window
            Long refundAmountPaise,
            String razorpayRefundId) {}
}
