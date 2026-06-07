package com.oneday.orders.dto;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;

import java.time.Instant;

/**
 * One row of the admin orders-database view ({@code GET /api/v1/admin/shipments}). A read-only
 * projection of {@link com.oneday.orders.domain.Shipment} — never used for booking. Field names
 * serialise to snake_case via the global Jackson config.
 */
public record ShipmentSummaryResponse(
        String shipmentRef,
        CustomerType customerType,
        DeliveryType deliveryType,
        ShipmentState state,
        PickupType pickupType,
        PaymentMode paymentMode,
        String originCity,
        String originPincode,
        String destCity,
        String destPincode,
        String senderName,
        String receiverName,
        Integer chargeableWeightGrams,
        Long totalPricePaise,
        Instant createdAt,
        Instant cancelledAt,
        // Custody model: the city (origin or dest) currently holding operational authority, and
        // whether the requesting viewer is that custodian (true only for the station manager whose
        // city currently owns the parcel; null/false for admin oversight and the non-custody city).
        String custodyCity,
        boolean canAct) {
}
