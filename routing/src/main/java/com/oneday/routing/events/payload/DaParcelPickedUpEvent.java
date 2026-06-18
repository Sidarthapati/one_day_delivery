package com.oneday.routing.events.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// PROVISIONAL contract for M5's DA-pickup event (oneday.da.events). Finalize with M5 owner.
@JsonIgnoreProperties(ignoreUnknown = true)
public record DaParcelPickedUpEvent(
        UUID parcelId,
        UUID cityId,
        UUID daId,
        LocalDate validDate,
        Instant pickedUpAt) {
}
