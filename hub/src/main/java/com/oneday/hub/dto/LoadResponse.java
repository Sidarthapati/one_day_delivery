package com.oneday.hub.dto;

import com.oneday.hub.domain.HubLoadSnapshot;

import java.time.Instant;
import java.util.UUID;

/** Live overload snapshot for the ops console / M10 (§11, §14.2). */
public record LoadResponse(
        UUID hubId,
        String waveKey,
        int inboundCount,
        int awaitingSort,
        int standOccupancyPct,
        Instant projectedClearAt,
        boolean overloaded,
        Instant snapshotAt) {

    public static LoadResponse from(HubLoadSnapshot s) {
        return new LoadResponse(s.getHubId(), s.getWaveKey(), s.getInboundCount(), s.getAwaitingSort(),
                s.getStandOccupancyPct(), s.getProjectedClearAt(), s.isOverloaded(), s.getSnapshotAt());
    }
}
