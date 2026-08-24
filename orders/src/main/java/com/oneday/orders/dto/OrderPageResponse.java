package com.oneday.orders.dto;

import java.util.List;

/** A page of the admin Order view. Field names serialise to snake_case via the global Jackson config. */
public record OrderPageResponse(
        List<OrderSummaryResponse> orders,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
