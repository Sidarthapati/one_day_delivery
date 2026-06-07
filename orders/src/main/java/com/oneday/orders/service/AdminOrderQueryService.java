package com.oneday.orders.service;

import com.oneday.orders.dto.ShipmentPageResponse;

/**
 * Read-only orders-database access for ADMIN tooling. Lists shipments across all customers
 * (most-recent first), optionally filtered by state. This is the admin counterpart to booking:
 * an ADMIN reads the orders database here but cannot place an order.
 */
public interface AdminOrderQueryService {

    /**
     * @param stateFilter optional {@code ShipmentState} name to filter by; null/blank → all states
     * @param cityScope   null → all cities (admin oversight); otherwise restrict to shipments whose
     *                    origin OR destination is this city (a station manager's own city). Also
     *                    used to compute per-row {@code canAct} against the custody model.
     * @param page        zero-based page index
     * @param size        page size (clamped to a sane maximum by the implementation)
     * @return a page of shipment summaries, newest first
     */
    ShipmentPageResponse listShipments(String stateFilter, String cityScope, int page, int size);
}
