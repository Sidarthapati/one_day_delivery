package com.oneday.routing.service.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Seam to M5/M4. The collect-side manifest is filled from the first-mile parcels a DA has
 * accumulated (PICKED_UP) through the day (§12.2). Until that feed exists,
 * {@link NoOpDaAccumulationPort} returns nothing.
 */
public interface DaAccumulationPort {

    /** First-mile parcels a DA has picked up and is holding for the next van collect. */
    List<AccumulatedParcel> collectedAwaitingPickup(UUID daId, LocalDate date);

    /** A first-mile parcel in a DA's custody awaiting collection by the van. */
    record AccumulatedParcel(
            UUID parcelId,
            UUID daId,
            Instant pickedUpAt) {
    }
}
