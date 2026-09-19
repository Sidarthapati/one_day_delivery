package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A flight bag was opened lazily for a (flight, date, dest_hub) on a resolved stand (§7.2).
 *
 * <p>{@code parcelId} is set on the PER-PARCEL emit (when a parcel is placed into the bag by the
 * sort) so M4's {@code HubEventsConsumer} can transition that shipment ORIGIN_HUB_PROCESSING →
 * IN_TAKEOFF_BAG; it is null on the bag-level lazy-open emit (no single parcel to advance, so the
 * consumer's {@code shipmentId==null} guard skips it). It maps to {@code HubEvent.shipmentId} via
 * that record's {@code @JsonAlias("parcel_id")}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BagCreatedEvent(
        UUID bagId,
        UUID cityId,
        UUID hubId,
        String flightNo,
        LocalDate flightDate,
        String destHub,
        String standNo,
        UUID parcelId) implements HubEventPayload {

    @Override
    public HubEventType eventType() {
        return HubEventType.BAG_CREATED;
    }

    @Override
    public String partitionKey() {
        return bagId != null ? bagId.toString() : null;
    }
}
