package com.oneday.routing.dto;

import com.oneday.routing.domain.VanLiveStatus;

import java.time.Instant;
import java.util.UUID;

/** One van's latest position + lateness for the ops live map ({@code GET /routing/vans/{cityId}/live}). */
public record VanLiveStatusResponse(
        UUID vanId,
        UUID cityId,
        UUID routePlanId,
        Double lastLat,
        Double lastLon,
        Instant lastSeenAt,
        Integer currentStopSeq,
        Integer minutesLate) {

    public static VanLiveStatusResponse from(VanLiveStatus s) {
        return new VanLiveStatusResponse(
                s.getVanId(), s.getCityId(), s.getRoutePlanId(),
                s.getLastLat(), s.getLastLon(), s.getLastSeenAt(),
                s.getCurrentStopSeq(), s.getMinutesLate());
    }
}
