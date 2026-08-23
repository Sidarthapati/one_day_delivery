package com.oneday.barcode.adapter;

import com.oneday.barcode.repository.ScanLedgerRepository;
import com.oneday.common.port.ShipmentScanTrailPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** The single {@link ShipmentScanTrailPort} implementation — reads the append-only M8 scan ledger. */
@Component
class ShipmentScanTrailPortAdapter implements ShipmentScanTrailPort {

    private final ScanLedgerRepository scanLedgerRepository;

    ShipmentScanTrailPortAdapter(ScanLedgerRepository scanLedgerRepository) {
        this.scanLedgerRepository = scanLedgerRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScanTrailEntry> trailFor(UUID shipmentId) {
        return scanLedgerRepository.findByShipmentIdOrderByScannedAtAsc(shipmentId).stream()
                .map(e -> new ScanTrailEntry(
                        e.getScanType(), e.getLocationType(), e.getLocationId(), e.getActorId(), e.getScannedAt()))
                .toList();
    }
}
