package com.oneday.routing.service.port;

import com.oneday.common.port.ScanLedgerPort;
import com.oneday.common.port.ScanLedgerPort.VanCustodyScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fallback that logs van scans when M8 is absent (routing-only test slices). In the assembled app
 * M8's {@code @Primary} {@code ScanLedgerPortAdapter} wins and writes to the append-only ledger.
 */
@Component
class NoOpScanLedgerPort implements ScanLedgerPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpScanLedgerPort.class);

    @Override
    public void recordVanScan(VanCustodyScan scan) {
        log.info("VAN_SCAN (stub, M8 not integrated) type={} parcelId={} vanId={} daId={} at={}",
                scan.type(), scan.parcelId(), scan.vanId(), scan.counterpartyDaId(), scan.scannedAt());
    }
}
