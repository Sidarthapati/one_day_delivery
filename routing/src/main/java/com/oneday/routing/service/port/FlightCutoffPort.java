package com.oneday.routing.service.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Seam to M9 (airline). First-mile parcels carry a hub-arrival deadline derived from the committed
 * flight cutoff minus the end-to-end tail {@code cutoff − (sort + bag + shuttle)} (§12.2, the Q2
 * budget). Until M9 ships, {@link NoOpFlightCutoffPort} returns empty (no cutoff known).
 */
public interface FlightCutoffPort {

    /** The earliest outbound flight cutoff for the city/date, or empty if unknown. */
    Optional<Instant> outboundFlightCutoff(UUID cityId, LocalDate date);
}
