package com.oneday.routing.service.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Seam to M7 (hub). The deliver-side manifest is built from M7's sort output — the parcels
 * "ready to load for delivery" (M6-D-015, §12.1). The buffer-backed impl reads accumulated
 * sorted-for-delivery events; until M7 publishes, the buffer stays empty.
 */
public interface HubSortPort {

    /** Parcels sorted for outbound delivery and ready to load onto a van, for this city/operating day. */
    List<ReadyForDeliveryParcel> readyForDelivery(UUID cityId, LocalDate date);

    /** A parcel M7 has sorted for delivery; M6 resolves destinationHexId → DA → vertex/van/loop. */
    record ReadyForDeliveryParcel(
            UUID parcelId,
            UUID destinationHexId,
            Instant readyAt,
            Instant slaDeadline) {
    }
}
