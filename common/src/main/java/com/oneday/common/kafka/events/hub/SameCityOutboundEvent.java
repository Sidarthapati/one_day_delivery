package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.util.UUID;

/**
 * An intra-city parcel that does not fly: origin hub IS the dest hub (§12). Signals M4/M10 to
 * collapse the air legs of the SLA; M7 then re-enters the inbound sort directly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SameCityOutboundEvent(
        UUID shipmentId,
        UUID cityId,
        UUID hubId) implements HubEventPayload {

    @Override
    public HubEventType eventType() {
        return HubEventType.SAMECITY_OUTBOUND;
    }

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }
}
