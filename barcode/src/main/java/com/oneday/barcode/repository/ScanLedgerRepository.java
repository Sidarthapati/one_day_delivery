package com.oneday.barcode.repository;

import com.oneday.barcode.domain.ScanLedgerEntry;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Deliberately extends the bare {@link Repository} marker, not {@code JpaRepository}: the ledger is
 * append-only (V8_1 trigger backstops it), so no {@code delete*}/bulk-update method is exposed at all —
 * only insert ({@link #save}) and the "who touched this box, when, where" trail lookups. The DB
 * trigger is the last line of defence; this interface is the first (mutation isn't even reachable).
 */
public interface ScanLedgerRepository extends Repository<ScanLedgerEntry, UUID> {

    /** Insert one immutable scan row. New entities (null id) persist; there is no update path by design. */
    ScanLedgerEntry save(ScanLedgerEntry entry);

    /** Full ordered scan trail for a shipment (the spine key). */
    List<ScanLedgerEntry> findByShipmentIdOrderByScannedAtAsc(UUID shipmentId);

    /** Same trail looked up by the physical barcode string a scan gun reads off the box. */
    List<ScanLedgerEntry> findByParcelIdOrderByScannedAtAsc(String parcelId);
}
