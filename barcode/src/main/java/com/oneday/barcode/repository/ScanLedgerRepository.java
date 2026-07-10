package com.oneday.barcode.repository;

import com.oneday.barcode.domain.ScanLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Read + insert only. The ledger is append-only (V8_1 trigger backstops it), so the inherited
 * {@code delete*}/update paths are never called — the "who touched this box, when, where" trail
 * is queried, never mutated. The two lookups below are that query, by either identity (design D-001).
 */
public interface ScanLedgerRepository extends JpaRepository<ScanLedgerEntry, UUID> {

    /** Full ordered scan trail for a shipment (the spine key). */
    List<ScanLedgerEntry> findByShipmentIdOrderByScannedAtAsc(UUID shipmentId);

    /** Same trail looked up by the physical barcode string a scan gun reads off the box. */
    List<ScanLedgerEntry> findByParcelIdOrderByScannedAtAsc(String parcelId);
}
