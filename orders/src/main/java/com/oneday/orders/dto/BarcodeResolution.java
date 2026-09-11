package com.oneday.orders.dto;

import com.oneday.common.domain.enums.ShipmentState;

import java.util.UUID;

/**
 * Resolves a scanned physical barcode to the shipment a scan should attach to right now (R1: reuse the
 * same barcode for the return). The original and its return child {@code <ref>_R} share one barcode; a
 * scan of that label routes to the live return child once an RTO is under way, else to the original.
 *
 * @param parcelId    the scanned barcode string
 * @param shipmentId  the ACTIVE shipment this scan should record against
 * @param shipmentRef its reference
 * @param state       its current state
 * @param isReturn    true ⇔ the barcode now belongs to a return leg (the {@code <ref>_R} child)
 * @param originalRef the original shipment's ref when {@code isReturn}; null otherwise
 */
public record BarcodeResolution(
        String parcelId,
        UUID shipmentId,
        String shipmentRef,
        ShipmentState state,
        boolean isReturn,
        String originalRef) {
}
