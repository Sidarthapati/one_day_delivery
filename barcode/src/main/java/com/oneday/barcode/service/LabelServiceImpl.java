package com.oneday.barcode.service;

import com.oneday.barcode.repository.ScanLedgerRepository;
import com.oneday.common.kafka.enums.ScanEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
class LabelServiceImpl implements LabelService {

    private final ScanLedgerRepository repository;
    private final ParcelIdGenerator generator;
    private final ScanLedgerService ledger;

    LabelServiceImpl(ScanLedgerRepository repository, ParcelIdGenerator generator, ScanLedgerService ledger) {
        this.repository = repository;
        this.generator = generator;
        this.ledger = ledger;
    }

    @Override
    @Transactional
    public String generateLabel(UUID shipmentId, String destCity, UUID actorId, UUID clientScanId) {
        // Idempotent: a shipment is labelled once. A retry returns the existing barcode and — crucially
        // — does NOT bump the per-hub counter (which would burn a sequence number for no parcel).
        var existing = repository.findFirstByShipmentIdAndScanType(shipmentId, ScanEventType.LABEL_GENERATED.name());
        if (existing.isPresent()) {
            return existing.get().getParcelId();
        }

        String hubIata = destCity.trim().toUpperCase();
        String parcelId = generator.next(hubIata);
        ledger.record(new ScanCommand(
                shipmentId, parcelId, null, ScanEventType.LABEL_GENERATED.name(),
                "DA", actorId, actorId, null, Instant.now(), clientScanId));
        return parcelId;
    }
}
