package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A flight bag was sealed at cutoff; its contents are frozen and a manifest generated (§7.3) → M9, M10.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BagSealedEvent(
        UUID bagId,
        String flightNo,
        LocalDate flightDate,
        String standNo,
        int parcelCount,
        int weightGrams) implements HubEventPayload {

    @Override
    public HubEventType eventType() {
        return HubEventType.BAG_SEALED;
    }

    @Override
    public String partitionKey() {
        return bagId != null ? bagId.toString() : null;
    }
}
