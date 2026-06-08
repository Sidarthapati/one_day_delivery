package com.oneday.orders.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oneday.common.domain.enums.ShipmentState;

/**
 * Response for {@code DELETE /api/v1/{b2c|b2b}/shipments/{ref}}.
 *
 * <p>The {@code refund} block is present only for a PREPAID retail cancellation that initiated a
 * Razorpay refund. COD and B2B (credit reversal) cancellations omit it ({@code NON_NULL}).
 * Field names serialise to snake_case via the global Jackson config.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CancellationResponse(
        String shipmentRef,
        ShipmentState state,
        RefundSummary refund) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefundSummary(
            String status,            // REFUND_INITIATED
            Integer estimatedDays,    // typical Razorpay settlement window
            Long refundAmountPaise,
            String razorpayRefundId) {}
}
