package com.oneday.hub.dto;

import com.oneday.hub.domain.DeliveryBagItem;

import java.time.Instant;
import java.util.UUID;

/** Per-parcel staging row for the dest operator view (§14.2). */
public record StagingResponse(
        UUID parcelId,
        String shipmentRef,
        UUID destHexId,
        UUID deliveryBagId,
        UUID daTerritoryId,
        UUID standId,
        String dropType,
        String status,
        Instant stagedAt) {

    public static StagingResponse from(DeliveryBagItem i) {
        return new StagingResponse(i.getParcelId(), i.getShipmentRef(), i.getDestHexId(),
                i.getDeliveryBagId(), i.getDaTerritoryId(), i.getStandId(),
                i.getDropType().name(), i.getStatus().name(), i.getStagedAt());
    }
}
