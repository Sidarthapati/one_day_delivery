package com.oneday.orders.service;

import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.dto.OrderCancellationSummary;

/**
 * B2B "order repair": while an order has not yet been fully picked up, a business-portal merchant may
 * add a shipment to it (charged to wallet/credit, subject to the DA vehicle-capacity gate) or cancel
 * the whole order (each eligible child refunded). Removing a single shipment is just cancelling that
 * shipment — the existing per-shipment cancel path, now rollup-aware.
 */
public interface OrderRepairService {

    /**
     * Adds one shipment to an existing order (books it onto the same parent, charging the account).
     * Enforces account ownership, order editability, and DA capacity before booking.
     */
    BookingResponse addShipment(String orderRef, B2bBookingRequest request,
                                String idempotencyKey, String userId);

    /** Cancels every still-eligible child of the order; reports the rest as skipped. */
    OrderCancellationSummary cancelOrder(String orderRef, String reason, String userId);

    /** The order can no longer be edited (every child is already past pickup) → 409. */
    class OrderNotEditableException extends RuntimeException {
        public OrderNotEditableException(String message) { super(message); }
    }
}
