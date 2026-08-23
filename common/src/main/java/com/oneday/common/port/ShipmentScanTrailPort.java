package com.oneday.common.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read a shipment's append-only scan trail, time-ordered, without importing the barcode module.
 * Implemented in barcode (M8, over the immutable scan ledger); consumed in orders (M4) to weave scans
 * into the unified per-parcel event timeline. Same cross-module read pattern as {@link ShipmentRefPort}.
 */
public interface ShipmentScanTrailPort {

    /** The shipment's scans, oldest first; empty if none. */
    List<ScanTrailEntry> trailFor(UUID shipmentId);

    /**
     * One scan on the trail — only {@code common}-safe types.
     *
     * @param scanType     the scan kind (a {@code ScanEventType} or van scan name, kept as text)
     * @param locationType where it happened (hub / van / DA / …), or null
     * @param locationId   the node id, or null
     * @param actorId      who scanned, or null
     * @param scannedAt    device fix time
     */
    record ScanTrailEntry(String scanType, String locationType, UUID locationId, UUID actorId, Instant scannedAt) {
    }
}
