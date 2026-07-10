package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A flight bag was opened lazily for a (flight, date, dest_hub) on a resolved stand (§7.2).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BagCreatedEvent(
        UUID bagId,
        UUID cityId,
        UUID hubId,
        String flightNo,
        LocalDate flightDate,
        String destHub,
        String standNo) implements HubEventPayload {

    @Override
    public HubEventType eventType() {
        return HubEventType.BAG_CREATED;
    }

    @Override
    public String partitionKey() {
        return bagId != null ? bagId.toString() : null;
    }
}
