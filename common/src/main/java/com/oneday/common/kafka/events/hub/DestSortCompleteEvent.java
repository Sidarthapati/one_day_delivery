package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per parcel: destination sortation is complete (the parcel is staged on its delivery stand, §8.2).
 * Consumed by M10 for the dest-hub leg's SLA accounting. Keyed by the parcel so per-parcel ordering
 * holds; {@code wave} carries the operating day for roll-up.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DestSortCompleteEvent(
        UUID parcelId,
        UUID cityId,
        UUID hubId,
        LocalDate wave,
        Instant completedAt) implements HubEventPayload {

    @Override
    public HubEventType eventType() {
        return HubEventType.DEST_SORT_COMPLETE;
    }

    @Override
    public String partitionKey() {
        return parcelId != null ? parcelId.toString() : null;
    }
}
