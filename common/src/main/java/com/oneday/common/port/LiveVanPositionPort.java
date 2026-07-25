package com.oneday.common.port;

import java.util.Optional;
import java.util.UUID;

/**
 * The live GPS of the van currently carrying a shipment (between hub and DA meeting points).
 * Implemented in M6 (routing) over {@code van_manifest_item → van_manifest → van_live_status};
 * consumed by the M4 tracking read path. In v1 a routing {@code parcelId} is the shipment UUID
 * (routing D-001), so the shipment id is passed straight through.
 */
public interface LiveVanPositionPort {

    /** The carrying van's latest GPS, or empty if the shipment isn't on a van / no fix yet. */
    Optional<LivePosition> forShipment(UUID shipmentId);
}
