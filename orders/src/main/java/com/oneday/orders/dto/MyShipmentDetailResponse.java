package com.oneday.orders.dto;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;

import java.time.Instant;
import java.util.UUID;

/**
 * Full detail of one of the caller's own shipments ({@code GET /api/v1/shipments/mine/{ref}}) — the
 * record straight from the {@code shipments} row, scoped to bookings the caller placed. Powers the
 * "click a booking to see its DB record" drill-down on the demo customer page. Field names serialise
 * to snake_case via the global Jackson config.
 */
public record MyShipmentDetailResponse(
        String shipmentRef,
        CustomerType customerType,
        ShipmentState state,
        String stateLabel,
        DeliveryType deliveryType,
        PickupType pickupType,
        DropType dropType,
        PaymentMode paymentMode,
        // sender / origin
        String senderName,
        String senderPhone,
        String senderEmail,
        String originLine1,
        String originCity,
        String originPincode,
        Double originLat,
        Double originLon,
        UUID originTileId,
        // receiver / destination
        String receiverName,
        String receiverPhone,
        String receiverEmail,
        String destLine1,
        String destCity,
        String destPincode,
        Double destLat,
        Double destLon,
        UUID destTileId,
        // parcel + money (paise)
        Integer weightGrams,
        Integer volumetricWeightGrams,
        Integer chargeableWeightGrams,
        Long declaredValuePaise,
        Long quotedPricePaise,
        Long taxPaise,
        Long totalPricePaise,
        // lifecycle
        Instant createdAt,
        Instant cancelledAt,
        // live flight position — present only while the parcel's flight is airborne (M9, §8)
        Double currentLat,
        Double currentLon,
        Instant positionAsOf,
        String positionStatus) {
}
