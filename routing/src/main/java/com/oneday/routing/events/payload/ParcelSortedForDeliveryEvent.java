package com.oneday.routing.events.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// PROVISIONAL contract for M7's sorted-for-delivery event (oneday.hub.events). Finalize with M7 owner.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParcelSortedForDeliveryEvent(
        UUID parcelId,
        UUID cityId,
        UUID destinationHexId,
        LocalDate validDate,
        Instant sortedAt,
        Instant slaDeadline) {
}
