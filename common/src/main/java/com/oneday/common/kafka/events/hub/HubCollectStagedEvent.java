package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.util.UUID;

/**
 * Per parcel: a HUB_COLLECT parcel has been placed on the destination hub's collect shelf (§8.2).
 * Consumed by M4 to advance the shipment {@code DEST_HUB_PROCESSING → AWAITING_HUB_COLLECT}, which
 * opens the customer-collect lifecycle ({@code HUB_COLLECT_COMPLETED → HUB_COLLECTED}). This is the
 * event OD-9 left open in {@code orders.HubEventsConsumer}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HubCollectStagedEvent(
        UUID shipmentId,
        UUID cityId,
        UUID hubId) implements HubEventPayload {

    @Override
    public HubEventType eventType() {
        return HubEventType.HUB_COLLECT_STAGED;
    }

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }
}
