package com.oneday.orders.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oneday.common.domain.enums.CustomerType;

import java.time.Instant;

/**
 * One row of the Order → N Shipments view — a booking grouping the business and customer consoles
 * render as a card that expands to its shipments. {@code status} is the rollup over the child
 * shipments (see {@link com.oneday.orders.service.OrderStatusReducer}). Field names serialise to
 * snake_case via the global Jackson config.
 */
public record OrderSummaryResponse(
        String orderRef,
        CustomerType customerType,
        String status,               // OrderStatusReducer.OrderStatus name
        String statusLabel,          // human-readable
        int parcelCount,
        long totalPricePaise,
        String originCity,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String purchaseOrderRef,
        Instant createdAt) {
}
