package com.oneday.hub.dto;

import com.oneday.hub.domain.DeliveryBag;

import java.time.LocalDate;
import java.util.UUID;

/** A delivery bag for the ops view: the unit a van loads (or a DA hub-collects) in one move (§8). */
public record DeliveryBagResponse(
        UUID bagId,
        String bagKind,
        LocalDate bagDate,
        UUID routePlanId,
        UUID loopId,
        UUID daTerritoryId,
        UUID zoneId,
        UUID standId,
        String status,
        int parcelCount,
        int weightGrams) {

    public static DeliveryBagResponse from(DeliveryBag b) {
        return new DeliveryBagResponse(b.getId(), b.getBagKind().name(), b.getBagDate(),
                b.getRoutePlanId(), b.getLoopId(), b.getDaTerritoryId(), b.getZoneId(),
                b.getCurrentStandId(), b.getStatus().name(), b.getParcelCount(), b.getWeightGrams());
    }
}
