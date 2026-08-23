package com.oneday.barcode.api;

import com.oneday.barcode.domain.ScanLedgerEntry;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a parcel's scan trail — the read shape of {@link ScanLedgerEntry} for the ops timeline
 * (issue #125). {@code scannedAt} is the physical scan time; {@code recordedAt} is when we stored it.
 */
public record ScanLedgerView(
        UUID id,
        String scanType,
        String locationType,
        UUID locationId,
        UUID actorId,
        UUID counterpartyId,
        String parcelId,
        UUID bagId,
        Instant scannedAt,
        Instant recordedAt) {

    public static ScanLedgerView from(ScanLedgerEntry e) {
        return new ScanLedgerView(e.getId(), e.getScanType(), e.getLocationType(), e.getLocationId(),
                e.getActorId(), e.getCounterpartyId(), e.getParcelId(), e.getBagId(),
                e.getScannedAt(), e.getRecordedAt());
    }
}
