package com.oneday.orders.service;

import com.oneday.orders.dto.MyShipmentDetailResponse;
import com.oneday.orders.dto.MyShipmentSummaryResponse;
import com.oneday.orders.dto.ShipmentLabelResponse;

import java.util.List;
import java.util.Optional;

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

    /**
     * Full detail for one of the caller's shipments, by reference. Scoped to the caller — a shipment
     * the caller did not book is treated as not found.
     *
     * @param userId      the authenticated caller's id (M1 user UUID, as a string)
     * @param shipmentRef the shipment reference (e.g. {@code 1DD-DELHI-20260629-00010})
     * @return the detail, or empty if no such ref exists for this caller
     */
    Optional<MyShipmentDetailResponse> myShipmentDetail(String userId, String shipmentRef);

    /**
     * Print-ready shipping-label data for one of the caller's shipments (Model A: the merchant
     * prints and affixes it). Scoped to the caller — a shipment they did not book is not found.
     * The seller block is populated from the caller's B2B account when applicable.
     *
     * @param userId      the authenticated caller's id (M1 user UUID, as a string)
     * @param shipmentRef the shipment reference
     * @return the label data, or empty if no such ref exists for this caller
     */
    Optional<ShipmentLabelResponse> shipmentLabel(String userId, String shipmentRef);
}
