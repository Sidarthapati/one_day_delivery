package com.oneday.orders.service;

import com.oneday.orders.dto.BarcodeResolution;
import com.oneday.orders.dto.ShipmentInfo;

import java.util.Optional;

/**
 * Public, read-only lookup of a shipment's operational facts (confirmed weight, drop type, dest,
 * SLA) by its reference / barcode string. The cross-module seam M7 (hub) wires for confirmed bag
 * weight and the destination drop branch — callers import this interface and {@link ShipmentInfo},
 * never the {@code Shipment} entity (CLAUDE.md cross-module rule).
 */
public interface ShipmentLookupService {

    /** @param shipmentRef the shipment reference / barcode string (e.g. {@code BLR-20260627-000042}). */
    Optional<ShipmentInfo> findByRef(String shipmentRef);

    /**
     * Resolve a scanned label to the shipment a scan should attach to now (R1). The return reuses the
     * original parcel's physical label, so the scanned {@code code} may be the barcode (parcel_id) or
     * the shipment ref; either way this prefers the live return child {@code <ref>_R} once an RTO is
     * under way, so a re-scan of the same label routes to the return, not the original.
     *
     * @param code the scanned label — a barcode (parcel_id) or a shipment ref
     * @return the active shipment for that label, or empty if unknown
     */
    Optional<BarcodeResolution> findActiveByParcelId(String code);
}
