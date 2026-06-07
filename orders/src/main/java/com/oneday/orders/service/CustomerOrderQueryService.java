package com.oneday.orders.service;

import com.oneday.orders.dto.MyShipmentSummaryResponse;

import java.util.List;

/**
 * Read-only access to a customer's own booking history. The customer-facing counterpart to
 * {@link AdminOrderQueryService}: a customer sees only the shipments they themselves booked
 * (matched on {@code booked_by_user_id}), across all sessions.
 */
public interface CustomerOrderQueryService {

    /**
     * @param userId the authenticated caller's id (M1 user UUID, as a string)
     * @param limit  maximum number of rows to return, newest first (clamped to a sane maximum)
     * @return the caller's shipments, newest first; empty if the id is unknown/unparseable
     */
    List<MyShipmentSummaryResponse> myShipments(String userId, int limit);
}
