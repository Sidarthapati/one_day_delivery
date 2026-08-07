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
        /** Null iff standId is null — resolved by the caller, the entity doesn't join to Stand. */
        String standNo,
        String dropType,
        String status,
        Instant stagedAt) {

    public static StagingResponse from(DeliveryBagItem i, String standNo) {
        return new StagingResponse(i.getParcelId(), i.getShipmentRef(), i.getDestHexId(),
                i.getDeliveryBagId(), i.getDaTerritoryId(), i.getStandId(), standNo,
                i.getDropType().name(), i.getStatus().name(), i.getStagedAt());
    }
}
