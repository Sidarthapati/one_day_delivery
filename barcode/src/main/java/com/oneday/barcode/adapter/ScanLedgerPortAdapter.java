package com.oneday.barcode.adapter;

import com.oneday.barcode.service.ScanCommand;
import com.oneday.barcode.service.ScanLedgerService;
import com.oneday.common.port.ScanLedgerPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * The real M8 side of the M6 van-custody seam (PR4). Wins over routing's {@code NoOpScanLedgerPort}
 * ({@code @Primary}) in the assembled app; maps a {@link ScanLedgerPort.VanCustodyScan} to a
 * {@link ScanCommand} and writes it through the shared append-only engine.
 *
 * <p>Van scans are ledger-only — {@code ScanEventProducer} does not broadcast them (their type is a
 * {@code VanScanType}, not a {@code ScanEventType}), so routing keeps sole ownership of the manifest
 * lifecycle (M8 D-004).</p>
 */
@Component
@Primary
class ScanLedgerPortAdapter implements ScanLedgerPort {

    private final ScanLedgerService ledger;

    ScanLedgerPortAdapter(ScanLedgerService ledger) {
        this.ledger = ledger;
    }

    @Override
    public void recordVanScan(VanCustodyScan scan) {
        // D-001: in v1 the routing "parcelId" is the shipment UUID (the ledger spine).
        UUID shipmentId = scan.parcelId();
        String scanType = scan.type().name();

        // Van scans carry no device idempotency key, so a driver-app retry on flaky signal would
        // duplicate the row. Synthesize a deterministic key from (shipmentId, scanType) — each parcel
        // gets each van scan once — so the engine's client-key dedup swallows the replay (PR4 guard).
        UUID clientScanId = UUID.nameUUIDFromBytes(
                (shipmentId + ":" + scanType).getBytes(StandardCharsets.UTF_8));

        ledger.record(new ScanCommand(
                shipmentId, null, null, scanType,
                "VAN", scan.vanId(), scan.driverId(), scan.counterpartyDaId(),
                scan.scannedAt() != null ? scan.scannedAt() : Instant.now(), clientScanId));
    }
}
