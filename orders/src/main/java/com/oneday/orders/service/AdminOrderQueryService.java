package com.oneday.orders.service;

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
     * One parcel's full ops timeline by human ref: header + M4 state history merged with the M8 scan
     * trail, oldest first. {@code cityScope} null → any city (ADMIN); otherwise the shipment must touch
     * that city (origin or destination) or it 404s — the same read rule as {@link #listShipments}.
     * An unknown ref 404s.
     */
    ShipmentTimelineResponse timeline(String shipmentRef, String cityScope);
}
