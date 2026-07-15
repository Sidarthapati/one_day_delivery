package com.oneday.orders.service.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Seam to M9 (airline) — <b>read-only</b>. A shipment's live position while its parcel is airborne,
 * for the customer tracking page. Empty before the flight departs, after it lands, or if the parcel
 * was never booked on a flight. M9 is unbuilt in some environments, so {@link NoOpFlightTrackingPort}
 * always answers empty; the real M9 impl swaps in via {@code @Primary}.
 */
public interface FlightTrackingPort {

    Optional<LivePosition> currentPosition(UUID shipmentId);

    record LivePosition(double lat, double lon, Instant asOf, String status) {
    }
}
