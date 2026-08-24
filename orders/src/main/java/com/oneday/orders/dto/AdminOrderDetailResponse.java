package com.oneday.orders.dto;

import java.util.List;

/**
 * An order with its shipments expanded for the business/admin console
 * ({@code GET /api/v1/admin/orders/{orderRef}}) — the "click an order, see its parcels" drill-down.
 */
public record AdminOrderDetailResponse(
        OrderSummaryResponse order,
        List<ShipmentSummaryResponse> shipments) {
}
