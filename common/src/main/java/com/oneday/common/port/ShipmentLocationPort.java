package com.oneday.common.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolve a shipment's real drop location (the receiver's address), without importing the orders
 * module. Implemented in orders (M4) over the {@code Shipment} destination address; consumed in
 * dispatch (M5) so the delivery-from-hub path assigns against the actual lat/lon rather than a hex
 * centroid. Same cross-module pattern as {@link DaCronSchedulePort} — both sides depend only on
 * {@code common}.
 */
public interface ShipmentLocationPort {

    /** Real drop coordinates + destination tile of a shipment. */
    record DropLocation(double lat, double lon, UUID destTileId) {}

    /** The shipment's drop location, if the shipment exists and carries geocoded dest coordinates. */
    Optional<DropLocation> dropLocation(UUID shipmentId);
}
