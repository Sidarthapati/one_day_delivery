package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * A hub (wave) is over capacity (§11, M7-D-007) → M10 (dashboard) + station manager. In v1 this same
 * alert is the advisory booking-throttle signal M4 may consume. Never means a parcel was dropped —
 * M7 escalates, never discards.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HubOverloadAlertEvent(
        UUID cityId,
        UUID hubId,
        String waveKey,
        int inboundCount,
        int awaitingSort,
        int standOccupancyPct,
        Instant projectedClearAt,
        Instant snapshotAt) implements HubEventPayload {

    @Override
    public HubEventType eventType() {
        return HubEventType.HUB_OVERLOAD_ALERT;
    }

    @Override
    public String partitionKey() {
        return hubId != null ? hubId.toString() : null;
    }
}
