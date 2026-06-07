package com.oneday.routing.service.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Logs van scans locally until M8 (scan ledger) ships. When it lands, provide a real impl annotated
 * {@code @Primary} to write to M8's append-only ledger (as M3's pattern).
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
