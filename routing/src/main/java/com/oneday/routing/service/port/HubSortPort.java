package com.oneday.routing.service.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Seam to M7 (hub). The deliver-side manifest is built from M7's sort output — the parcels
 * "ready to load for delivery" (M6-D-015, §12.1). Until M7 ships, {@link NoOpHubSortPort}
 * returns nothing. The exact shape is finalized with the M7 owner when M7 starts.
 */
public interface HubSortPort {

    /** Parcels sorted for outbound delivery and ready to load onto a van, for this city. */
    List<ReadyForDeliveryParcel> readyForDelivery(UUID cityId);

    /** A parcel M7 has sorted for delivery; M6 resolves destinationHexId → DA → vertex/van/loop. */
    record ReadyForDeliveryParcel(
            UUID parcelId,
            UUID destinationHexId,
            Instant readyAt,
            Instant slaDeadline) {
    }
}
