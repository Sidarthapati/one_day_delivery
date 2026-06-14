package com.oneday.routing.dto;

import com.oneday.routing.domain.CityFleetConfig;

import java.time.Instant;
import java.util.UUID;

/** Per-city fleet + cycle/dwell knobs ({@code GET/PUT /routing/fleet/{cityId}}). */
public record FleetConfigResponse(
        UUID cityId,
        int vansAvailable,
        int capacityPackets,
        int cycleTimeMinMinutes,
        int cycleTimeMaxMinutes,
        int shuttleCadenceMinutes,
        int maxDaToVertexMinutes,
        int dwellMinutes,
        Instant updatedAt) {

    public static FleetConfigResponse from(CityFleetConfig c) {
        return new FleetConfigResponse(
                c.getCityId(), c.getVansAvailable(), c.getCapacityPackets(),
                c.getCycleTimeMinMinutes(), c.getCycleTimeMaxMinutes(), c.getShuttleCadenceMinutes(),
                c.getMaxDaToVertexMinutes(), c.getDwellMinutes(), c.getUpdatedAt());
    }
}
