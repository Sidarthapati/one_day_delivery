package com.oneday.orders.dto;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.ShipmentState;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a shipment for downstream operational modules (M7 hub, etc.) that need a
 * parcel's confirmed routing/weight facts without importing the {@code Shipment} entity. The public
 * counterpart to the internal entity — see {@link com.oneday.orders.service.ShipmentLookupService}.
 *
 * <p>{@code chargeableWeightGrams} is the billed/handling weight M7 accumulates into a flight bag.
 * {@code slaDeadline} is nullable until M10's commitment timestamp is wired.</p>
 */
public record ShipmentInfo(
        UUID shipmentId,
        String shipmentRef,
        ShipmentState state,
        int chargeableWeightGrams,
        DropType dropType,
        DeliveryType deliveryType,
        String originCity,
        String destCity,
        String destPincode,
        UUID destTileId,
        Instant slaDeadline) {
}
