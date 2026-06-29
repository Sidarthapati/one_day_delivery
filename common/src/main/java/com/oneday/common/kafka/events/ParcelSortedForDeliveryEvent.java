package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-parcel "sorted for delivery" event on {@code oneday.hub.events} — M7 emits it on destination-sort
 * completion; M6 ({@code HubFeedConsumer}) binds the parcel to a drop-van loop. Provisional contract
 * (M7-D-002); finalize with the M7 owner.
 *
 * <p>Lives in {@code common} (not routing) so every consumer of the hub exchange can deserialize it —
 * including M4's {@code HubEventsConsumer}, which takes the {@link DomainEvent} supertype and simply
 * ignores non-{@code HubEvent} payloads instead of rejecting them to the DLQ.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParcelSortedForDeliveryEvent(
        UUID parcelId,
        UUID cityId,
        UUID destinationHexId,
        LocalDate validDate,
        Instant sortedAt,
        Instant slaDeadline) implements DomainEvent {

    @Override
    public String partitionKey() {
        return parcelId != null ? parcelId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return "PARCEL_SORTED_FOR_DELIVERY";
    }
}
