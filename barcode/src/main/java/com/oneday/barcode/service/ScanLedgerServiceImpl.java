package com.oneday.barcode.service;

import com.oneday.barcode.domain.ScanLedgerEntry;
import com.oneday.barcode.events.ScanRecorded;
import com.oneday.barcode.repository.ScanLedgerRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ScanLedgerServiceImpl implements ScanLedgerService {

    private final ScanLedgerRepository repository;
    private final ApplicationEventPublisher events;

    ScanLedgerServiceImpl(ScanLedgerRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    @Override
    @Transactional
    public ScanLedgerEntry record(ScanCommand cmd) {
        // The scan physically happened — record it. Replay guard first (a flaky-signal retry re-sends
        // the same clientScanId): return the row that already stands rather than a duplicate.
        if (cmd.clientScanId() != null) {
            var existing = repository.findByClientScanId(cmd.clientScanId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        ScanLedgerEntry entry = repository.save(ScanLedgerEntry.builder()
                .shipmentId(cmd.shipmentId())
                .parcelId(cmd.parcelId())
                .bagId(cmd.bagId())
                .scanType(cmd.scanType())
                .locationType(cmd.locationType())
                .locationId(cmd.locationId())
                .actorId(cmd.actorId())
                .counterpartyId(cmd.counterpartyId())
                .scannedAt(cmd.scannedAt())
                .clientScanId(cmd.clientScanId())
                .build());

        // In-process signal; the outbound ScanEvent is published only AFTER this tx commits.
        events.publishEvent(new ScanRecorded(
                entry.getShipmentId(), entry.getParcelId(), entry.getScanType(), entry.getScannedAt()));
        return entry;
    }
}
