package com.oneday.hub.service.port;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.ShipmentState;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Seam to M4 (orders). M7 reads a parcel's confirmed weight (bag accumulation, §7.2), drop type
 * (dest branch, §8.2) and SLA (reschedule gate, §9) off the hot path. M4 is built, so this is
 * wired for real ({@link ShipmentInfoAdapter} → {@code orders.ShipmentLookupService}); the hub-local
 * {@link ParcelInfo} keeps the rest of M7 decoupled from the orders DTO.
 */
public interface ShipmentInfoPort {

    /** @param shipmentRef the parcel reference / barcode string. */
    Optional<ParcelInfo> lookup(String shipmentRef);

    record ParcelInfo(
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
}
