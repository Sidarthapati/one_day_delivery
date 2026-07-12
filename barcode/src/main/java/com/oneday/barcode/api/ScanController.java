package com.oneday.barcode.api;

import com.oneday.barcode.service.ScanCommand;
import com.oneday.barcode.service.LabelService;
import com.oneday.barcode.service.ScanLedgerService;
import com.oneday.common.kafka.enums.ScanEventType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * M8 scan entry doors. {@code /label} mints the parcel ID at first-mile pickup (PR2); the generic
 * {@code POST /api/v1/scan} records every other lifecycle scan (PR3) — hub gun, GHA, shuttle, DA,
 * hub counter — and lets {@link ScanEventProducer} fan the resulting {@code ScanEvent} to M4.
 *
 * <p>ponytail: authentication is the app-level JWT filter (same as {@code /label}); role-granular
 * gating (hub-operator vs GHA vs counter) is deferred — add when a non-DA role must be blocked here.</p>
 */
@RestController
@RequestMapping("/api/v1/scan")
class ScanController {

    private final LabelService labelService;
    private final ScanLedgerService ledger;

    ScanController(LabelService labelService, ScanLedgerService ledger) {
        this.labelService = labelService;
        this.ledger = ledger;
    }

    @PostMapping("/label")
    LabelResponse label(@Valid @RequestBody LabelRequest req) {
        String parcelId = labelService.generateLabel(
                req.shipmentId(), req.destCity(), req.actorId(), req.clientScanId());
        return new LabelResponse(req.shipmentId(), parcelId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    void scan(@Valid @RequestBody ScanRequest req) {
        ledger.record(new ScanCommand(
                req.shipmentId(), req.parcelId(), req.bagId(), validScanType(req.scanType()),
                req.locationType(), req.locationId(), req.actorId(), req.counterpartyId(),
                req.scannedAt() != null ? req.scannedAt() : Instant.now(), req.clientScanId()));
    }

    /**
     * This door records lifecycle scans only: the type must be a {@link ScanEventType} (van custody
     * scans arrive via the sync port), and {@code LABEL_GENERATED} has its own {@code /label} door
     * because it mints an id. Anything else → 400.
     */
    private static String validScanType(String scanType) {
        ScanEventType type;
        try {
            type = ScanEventType.valueOf(scanType);
        } catch (IllegalArgumentException notLifecycle) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown or non-lifecycle scan type: " + scanType);
        }
        if (type == ScanEventType.LABEL_GENERATED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "LABEL_GENERATED must use POST /api/v1/scan/label");
        }
        return type.name();
    }
}
