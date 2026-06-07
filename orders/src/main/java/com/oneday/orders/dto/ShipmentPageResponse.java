package com.oneday.orders.dto;

import java.util.List;

/**
 * A page of the admin orders-database view. Field names serialise to snake_case via the
 * global Jackson config.
 */
public record ShipmentPageResponse(
        List<ShipmentSummaryResponse> shipments,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
