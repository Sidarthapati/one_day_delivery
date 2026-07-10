package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.util.UUID;

/**
 * A scanned parcel resolved to a stand from the live sort plan (§7.1/§8.1) → operator console, M8.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StandAssignedEvent(
        UUID shipmentId,
        UUID cityId,
        UUID hubId,
        String standNo,
        String sortKey,
        String direction) implements HubEventPayload {

    @Override
    public HubEventType eventType() {
        return HubEventType.STAND_ASSIGNED;
    }

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }
}
