package com.oneday.hub.dto;

import com.oneday.hub.domain.BagStatus;
import com.oneday.hub.domain.FlightBag;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Flight-bag view for the operator console. */
public record BagResponse(
        UUID bagId,
        UUID cityId,
        UUID hubId,
        String flightNo,
        LocalDate flightDate,
        String originHub,
        String destHub,
        UUID currentStandId,
        BagStatus status,
        int parcelCount,
        int weightGrams,
        Instant bagCutoff,
        UUID manifestId,
        Instant sealedAt,
        Instant dispatchedAt) {

    public static BagResponse from(FlightBag b) {
        return new BagResponse(b.getId(), b.getCityId(), b.getHubId(), b.getFlightNo(), b.getFlightDate(),
                b.getOriginHub(), b.getDestHub(), b.getCurrentStandId(), b.getStatus(),
                b.getParcelCount(), b.getWeightGrams(), b.getBagCutoff(), b.getManifestId(),
                b.getSealedAt(), b.getDispatchedAt());
    }
}
