package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.ParcelOrder;
import com.oneday.orders.dto.OrderSummaryResponse;
import com.oneday.orders.service.OrderStatusReducer;
import com.oneday.orders.service.OrderStatusReducer.OrderStatus;

import java.util.Collection;

/** Builds the shared {@link OrderSummaryResponse} card used by both the customer and admin consoles. */
final class OrderSummaries {

    private OrderSummaries() {}

    static OrderSummaryResponse toSummary(ParcelOrder o, Collection<ShipmentState> childStates) {
        OrderStatus status = OrderStatusReducer.reduce(childStates);
        return new OrderSummaryResponse(
                o.getOrderRef(),
                o.getCustomerType(),
                status.name(),
                label(status),
                o.getParcelCount(),
                o.getTotalPricePaise(),
                o.getCityId(),
                o.getPurchaseOrderRef(),
                o.getCreatedAt());
    }

    static String label(OrderStatus status) {
        return switch (status) {
            case EMPTY -> "No parcels";
            case BOOKED -> "Booked";
            case IN_PROGRESS -> "In progress";
            case PARTIALLY_DELIVERED -> "Partially delivered";
            case DELIVERED -> "Delivered";
            case RETURNED -> "Returned to sender";
            case CANCELLED -> "Cancelled";
            case MIXED -> "Mixed";
        };
    }
}
