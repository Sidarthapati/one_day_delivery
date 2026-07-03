package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * M7 executed an M9-decided flight move (§9, M7-D-006): the named parcels were re-pointed from
 * {@code fromFlightNo} onto {@code toFlightNo}'s bag → M10 re-baselines the leg. {@code bagId} is the
 * <b>target</b> bag now carrying them. {@code manifestId} is the superseding manifest (may be null).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BagRescheduledEvent(
        UUID bagId,
        String fromFlightNo,
        String toFlightNo,
        LocalDate toFlightDate,
        String destHub,
        String reason,
        int parcelCount,
        String standNo,
        UUID manifestId) implements HubEventPayload {

    @Override
    public HubEventType eventType() {
        return HubEventType.BAG_RESCHEDULED;
    }

    @Override
    public String partitionKey() {
        return bagId != null ? bagId.toString() : null;
    }
}
