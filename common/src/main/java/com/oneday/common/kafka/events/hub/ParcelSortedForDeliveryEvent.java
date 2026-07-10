package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-parcel: M7 has sorted a destination parcel for last-mile delivery and staged it in its
 * route/territory bag (§8.2, M7-D-002). M6's {@code HubFeedConsumer} consumes this exact class and
 * binds the parcel to a van loop — one shared contract, so the {@code __TypeId__} the producer stamps
 * matches the listener's parameter and the feed can't land in the DLQ.
 *
 * <p>The first six fields are all the binder needs. The remaining fields are <b>additive</b>: they
 * expose M7's own route resolution + the bag the parcel sits in, useful to M6/ops but never required
 * for binding (M6 reads only the six).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParcelSortedForDeliveryEvent(
        UUID parcelId,
        UUID cityId,
        UUID destinationHexId,
        LocalDate validDate,
        Instant sortedAt,
        Instant slaDeadline,
        // ── additive (M6-tolerant) ──
        UUID daTerritoryId,
        UUID routePlanId,
        UUID vanId,
        UUID deliveryBagId,
        String standNo) implements HubEventPayload {

    @Override
    public HubEventType eventType() {
        return HubEventType.PARCEL_SORTED_FOR_DELIVERY;
    }

    @Override
    public String partitionKey() {
        return parcelId != null ? parcelId.toString() : null;
    }
}
