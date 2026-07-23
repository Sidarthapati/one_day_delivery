package com.oneday.common.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module seam between M6 (routing) and M8 (barcode). The van is a first-class custody node
 * (M6-D-014): M6 originates the four van scans, M8 records them immutably in its append-only ledger.
 *
 * <p>M8 provides the real {@code @Primary} implementation; routing keeps a no-op fallback for
 * routing-only test slices where the barcode module is absent. Van scans are ledger-only — M8 does
 * not re-broadcast them as {@code ScanEvent} (M8 D-004); routing owns the manifest lifecycle.</p>
 */
public interface ScanLedgerPort {

    /** Record one van custody scan in M8's append-only ledger. */
    void recordVanScan(VanCustodyScan scan);

    /** The four van custody transfer points (M6 §11.1). Both van and driver identity travel on the scan. */
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
