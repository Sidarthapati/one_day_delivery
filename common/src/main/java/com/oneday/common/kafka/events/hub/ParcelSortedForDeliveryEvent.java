package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-parcel: M7 has sorted a destination parcel for last-mile delivery and staged it in its
 * route/territory bag (§8.2, M7-D-002). M6 consumes this and binds the parcel to a van loop.
 *
 * <p>The first six fields are the <b>exact shape</b> of M6's provisional
 * {@code routing.events.payload.ParcelSortedForDeliveryEvent}, so swapping M6's buffer stub for
 * this real feed is a no-op for the binder. The remaining fields are <b>additive</b> (M6 ignores
 * them via its tolerant reader): they expose M7's own route resolution + the bag the parcel sits in,
 * useful to M6/ops but never required for binding.</p>
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
        UUID loopId,
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
