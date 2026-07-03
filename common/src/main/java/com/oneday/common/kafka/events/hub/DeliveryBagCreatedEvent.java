package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A destination delivery bag was opened lazily for a resolved route/territory on a stand (§8.1).
 * The inbound mirror of {@link BagCreatedEvent}; shares the {@code BAG_CREATED} discriminator,
 * distinguished by {@code direction = "INBOUND"} and the route/territory key it carries.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeliveryBagCreatedEvent(
        UUID bagId,
        UUID cityId,
        UUID hubId,
        String bagKind,
        UUID routePlanId,
        UUID vanId,
        UUID daTerritoryId,
        LocalDate bagDate,
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
