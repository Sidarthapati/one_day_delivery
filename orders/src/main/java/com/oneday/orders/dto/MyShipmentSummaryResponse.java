package com.oneday.orders.dto;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.ShipmentState;

import java.time.Instant;

/**
 * One row of a customer's own booking history ({@code GET /api/v1/shipments/mine}). A read-only
 * projection of {@link com.oneday.orders.domain.Shipment} scoped to the bookings the caller placed.
 * Unlike the admin view this carries no operational/custody fields — just what a customer needs to
 * recognise and track their parcel, including the customer-visible {@code stateLabel}. Field names
 * serialise to snake_case via the global Jackson config.
 */
public record MyShipmentSummaryResponse(
        String shipmentRef,
        CustomerType customerType,
        ShipmentState state,
        String stateLabel,
        String originCity,
        String destCity,
        Long totalPricePaise,
        Instant createdAt,
        Instant cancelledAt) {
}
