package com.oneday.barcode.service;

import java.util.UUID;

/**
 * The DA's "Generate Label" action at first-mile pickup (M8 §3.1). Mints the barcode, writes the
 * {@code LABEL_GENERATED} scan, and (after commit) emits the {@code ScanEvent} that M4 uses to fill
 * {@code shipments.parcel_id}. Idempotent per shipment — a retry returns the same barcode.
 */
public interface LabelService {

    /**
     * @param shipmentId  the shipment being labelled
     * @param destCity    destination hub IATA (e.g. "DEL") — the barcode's human-readable segment
     * @param actorId     the DA who scanned (recorded as the ledger actor)
     * @param clientScanId device idempotency key (may be null)
     * @return the barcode string, e.g. {@code 1DD-DEL-260711-000042}
     */
    String generateLabel(UUID shipmentId, String destCity, UUID actorId, UUID clientScanId);
}
