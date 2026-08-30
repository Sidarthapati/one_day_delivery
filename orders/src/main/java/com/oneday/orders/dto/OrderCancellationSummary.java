package com.oneday.orders.dto;

import java.util.List;

/**
 * Result of cancelling a whole order. Partial by design: every child shipment still within its
 * cancellation window is cancelled and refunded; any already past pickup is reported in {@code skipped}
 * (the merchant sees exactly which parcels could not be pulled back).
 */
public record OrderCancellationSummary(
        String orderRef,
        List<String> cancelled,
        List<SkippedShipment> skipped) {

    /** A child that could not be cancelled, with the state that blocked it. */
    public record SkippedShipment(String shipmentRef, String state) {}
}
