package com.oneday.routing.service.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Seam to M8 (scan ledger). The van is a first-class custody node (M6-D-014): M6 originates the
 * four van scans, M8 records them immutably. Until M8 ships, {@link NoOpScanLedgerPort} logs only.
 * Q12 (is the van a distinct M8 scan-location type?) is confirmed with the M8 owner in PR #6.
 */
public interface ScanLedgerPort {

    /** Record one van custody scan in M8's append-only ledger. */
    void recordVanScan(VanCustodyScan scan);

    /** The four van custody transfer points (§11.1). Both van and driver identity travel on the scan (Q14). */
    enum VanScanType {
        VAN_LOAD,    // hub → van
        VAN_TO_DA,   // van → DA  (deliver)
        DA_TO_VAN,   // DA → van  (collect)
        VAN_UNLOAD   // van → hub
    }

    record VanCustodyScan(
            UUID parcelId,
            VanScanType type,
            UUID vanId,
            UUID driverId,
            UUID counterpartyDaId,
            Instant scannedAt) {
    }
}
