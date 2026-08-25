package com.oneday.orders.service;

import com.oneday.orders.dto.AdminOrderDetailResponse;
import com.oneday.orders.dto.OrderPageResponse;
import com.oneday.orders.dto.ShipmentPageResponse;
import com.oneday.orders.dto.ShipmentSummaryResponse;
import com.oneday.orders.dto.ShipmentTimelineResponse;

import java.util.List;

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

    /**
     * Every shipment matching the same {@code stateFilter} + {@code cityScope} as {@link #listShipments},
     * newest first, without the page cap — backs the ops "export all matching" CSV. Bounded by a hard
     * safety ceiling in the implementation.
     */
    List<ShipmentSummaryResponse> exportShipments(String stateFilter, String cityScope);

    /**
     * Every shipment booked against one B2B account, newest first, without the page cap — backs the
     * merchant's self-service CSV export. Same row shape as {@link #exportShipments}. The caller
     * (a B2B controller) resolves and enforces account ownership, so this is not gated here.
     */
    List<ShipmentSummaryResponse> exportForAccount(java.util.UUID accountId);

    /**
     * One parcel's full ops timeline by human ref: header + M4 state history merged with the M8 scan
     * trail, oldest first. {@code cityScope} null → any city (ADMIN); otherwise the shipment must touch
     * that city (origin or destination) or it 404s — the same read rule as {@link #listShipments}.
     * An unknown ref 404s.
     */
    ShipmentTimelineResponse timeline(String shipmentRef, String cityScope);

    /**
     * A page of orders (Order → N Shipments), newest first — the business/admin console's order view.
     * {@code cityScope} null → all cities (ADMIN); otherwise restrict to orders placed in that origin
     * city (station-manager scope). Each row carries the rollup status over its child shipments.
     */
    OrderPageResponse listOrders(String cityScope, int page, int size);

    /**
     * One order with its shipments expanded, by order ref. {@code cityScope} null → any city (ADMIN);
     * otherwise the order's origin city must match or it 404s. An unknown ref 404s.
     */
    AdminOrderDetailResponse orderDetail(String orderRef, String cityScope);
}
