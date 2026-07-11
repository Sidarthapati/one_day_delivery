package com.oneday.barcode.service;

import java.time.Instant;
import java.util.UUID;

/**
 * One physical scan to append to the ledger (M8 §4) — the single input to {@link ScanLedgerService}.
 * {@code parcelId}/{@code bagId} are set per scan family (barcode string on parcel scans, bag id on
 * bag scans, both null on van scans). {@code clientScanId} is the device idempotency key.
 */
public record ScanCommand(
        UUID shipmentId,
        String parcelId,
        UUID bagId,
        String scanType,
        String locationType,
        UUID locationId,
        UUID actorId,
        UUID counterpartyId,
        Instant scannedAt,
        UUID clientScanId) {
}
