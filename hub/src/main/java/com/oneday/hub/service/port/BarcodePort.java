package com.oneday.hub.service.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Seam to M8 (barcode/scan ledger). M7 calls M8 to build the dual-number bag QR (flight + physical
 * stand, C7) and to read the latest scan at a point. M8 is unbuilt, so {@link LocalBarcodePort}
 * builds the label data locally and returns no scans; the real M8 impl swaps in via {@code @Primary}.
 * M7-D-010.
 */
public interface BarcodePort {

    /** The data string encoded on the bag's QR — carries both the flight number and physical stand. */
    String buildBagLabel(String flightNo, String standNo);

    /** The latest scan timestamp for a parcel at a scan point, if any (empty until M8 ships). */
    Optional<Instant> latestScanAt(UUID parcelId, String scanPoint);
}
