package com.oneday.orders.dto;

import java.util.List;

/**
 * A customer's order with its shipments expanded — the "click an order, see its parcels" drill-down
 * ({@code GET /api/v1/orders/mine/{orderRef}}). Scoped to the caller.
 */
public record MyOrderDetailResponse(
        OrderSummaryResponse order,
        List<MyShipmentSummaryResponse> shipments) {
}
