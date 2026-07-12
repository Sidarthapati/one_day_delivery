package com.oneday.barcode.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * One lifecycle scan from a physical scanner app (hub gun, GHA, shuttle, DA, hub counter) —
 * {@code POST /api/v1/scan}. Discriminated by {@code scanType}, which must be a {@code ScanEventType}
 * (van custody scans use the sync port, not this door; {@code LABEL_GENERATED} uses {@code /label}).
 *
 * <p>{@code bagId} is set on bag scans (M7 fans one bag QR out to N per-parcel calls, D-006).
 * {@code scannedAt} is the device wall-clock (defaults to now if omitted); {@code clientScanId}
 * makes the call idempotent on flaky signal.</p>
 */
public record ScanRequest(
        @NotNull UUID shipmentId,
        @NotBlank String scanType,
        @NotBlank String locationType,
        UUID locationId,
        UUID actorId,
        UUID counterpartyId,
        UUID bagId,
        String parcelId,
        Instant scannedAt,
        UUID clientScanId) {
}
