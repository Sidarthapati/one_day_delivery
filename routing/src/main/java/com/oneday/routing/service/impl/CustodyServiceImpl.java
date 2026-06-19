package com.oneday.routing.service.impl;

import com.oneday.routing.domain.HandoffDirection;
import com.oneday.routing.domain.ManifestItemStatus;
import com.oneday.routing.domain.ManifestStatus;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.domain.VanManifestItem;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.CustodyService;
import com.oneday.routing.service.model.CustodyResult;
import com.oneday.routing.service.model.VanCustodyCommand;
import com.oneday.routing.service.port.ScanLedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Records the four van custody scans (§11.1). The physical scan is written to M8's ledger
 * unconditionally (append-only truth, {@link ScanLedgerPort}); the manifest item then advances
 * only from its legal predecessor state (C12). Replays are idempotent; an out-of-order or
 * off-manifest scan is reported, never silently applied.
 */
@Service
class CustodyServiceImpl implements CustodyService {

    private static final Logger log = LoggerFactory.getLogger(CustodyServiceImpl.class);

    private static final List<ManifestItemStatus> TERMINAL =
            List.of(ManifestItemStatus.RECONCILED, ManifestItemStatus.HANDED_OFF, ManifestItemStatus.EXCEPTION);

    private final VanManifestItemRepository itemRepository;
    private final VanManifestRepository manifestRepository;
    private final ScanLedgerPort scanLedgerPort;

    CustodyServiceImpl(VanManifestItemRepository itemRepository, VanManifestRepository manifestRepository,
                       ScanLedgerPort scanLedgerPort) {
        this.itemRepository = itemRepository;
        this.manifestRepository = manifestRepository;
        this.scanLedgerPort = scanLedgerPort;
    }

    @Override
    @Transactional
    public CustodyResult record(VanCustodyCommand cmd) {
        // 1. The scan physically happened — record it in M8's immutable ledger before anything else.
        scanLedgerPort.recordVanScan(new ScanLedgerPort.VanCustodyScan(
                cmd.parcelId(), cmd.type(), cmd.vanId(), cmd.driverId(), cmd.counterpartyDaId(), cmd.scannedAt()));

        HandoffDirection direction = directionOf(cmd.type());
        VanManifestItem item = itemRepository.findByParcelId(cmd.parcelId()).stream()
                .filter(i -> i.getDirection() == direction && i.getStatus() != ManifestItemStatus.EXCEPTION)
                .findFirst()
                .orElse(null);
        if (item == null) {
            // Scanned a parcel this van wasn't carrying for this direction — mis-route; the stop
            // reconcile (HandoffService) will surface it as an EXTRA. Custody just records the scan.
            log.warn("Custody {} parcel {} not on any active manifest item — mis-route/extra", cmd.type(), cmd.parcelId());
            return CustodyResult.unknownParcel(cmd.parcelId(), cmd.type());
        }

        ManifestItemStatus next = nextStatus(cmd.type());
        if (item.getStatus() == next) {
            return CustodyResult.idempotent(cmd.parcelId(), cmd.type(), next); // replayed scan
        }
        ManifestItemStatus expectedPrev = prevStatus(cmd.type());
        if (item.getStatus() != expectedPrev) {
            log.warn("Custody {} parcel {} illegal: status {} (expected {}) — C12", cmd.type(), cmd.parcelId(),
                    item.getStatus(), expectedPrev);
            return CustodyResult.illegal(cmd.parcelId(), cmd.type(), item.getStatus());
        }

        applyTransition(item, cmd);
        itemRepository.save(item);
        advanceManifestLifecycle(item, cmd.type());
        return CustodyResult.recorded(cmd.parcelId(), cmd.type(), next);
    }

    // ── transition tables (§11.1) ──────────────────────────────────────────

    private void applyTransition(VanManifestItem item, VanCustodyCommand cmd) {
        switch (cmd.type()) {
            case VAN_LOAD -> { item.setStatus(ManifestItemStatus.LOADED); item.setLoadedAt(cmd.scannedAt()); }
            case VAN_TO_DA -> { item.setStatus(ManifestItemStatus.HANDED_OFF); item.setHandedOffAt(cmd.scannedAt()); }
            case DA_TO_VAN -> item.setStatus(ManifestItemStatus.ONBOARD);
            case VAN_UNLOAD -> item.setStatus(ManifestItemStatus.RECONCILED);
        }
    }

    private static HandoffDirection directionOf(ScanLedgerPort.VanScanType type) {
        return switch (type) {
            case VAN_LOAD, VAN_TO_DA -> HandoffDirection.DELIVER;
            case DA_TO_VAN, VAN_UNLOAD -> HandoffDirection.COLLECT;
        };
    }

    private static ManifestItemStatus prevStatus(ScanLedgerPort.VanScanType type) {
        return switch (type) {
            case VAN_LOAD -> ManifestItemStatus.PLANNED;
            case VAN_TO_DA -> ManifestItemStatus.LOADED;
            case DA_TO_VAN -> ManifestItemStatus.PLANNED;
            case VAN_UNLOAD -> ManifestItemStatus.ONBOARD;
        };
    }

    private static ManifestItemStatus nextStatus(ScanLedgerPort.VanScanType type) {
        return switch (type) {
            case VAN_LOAD -> ManifestItemStatus.LOADED;
            case VAN_TO_DA -> ManifestItemStatus.HANDED_OFF;
            case DA_TO_VAN -> ManifestItemStatus.ONBOARD;
            case VAN_UNLOAD -> ManifestItemStatus.RECONCILED;
        };
    }

    // First load seals the manifest (BUILDING → LOADED); the final unload reconciles it. The
    // in-loop IN_PROGRESS / RETURNED transitions are driven by van telemetry (PR7).
    private void advanceManifestLifecycle(VanManifestItem item, ScanLedgerPort.VanScanType type) {
        VanManifest manifest = manifestRepository.findById(item.getManifestId()).orElse(null);
        if (manifest == null) return;
        if (type == ScanLedgerPort.VanScanType.VAN_LOAD && manifest.getStatus() == ManifestStatus.BUILDING) {
            manifest.setStatus(ManifestStatus.LOADED);
            manifestRepository.save(manifest);
        } else if (type == ScanLedgerPort.VanScanType.VAN_UNLOAD && allItemsTerminal(manifest.getId())) {
            manifest.setStatus(ManifestStatus.RECONCILED);
            manifestRepository.save(manifest);
        }
    }

    private boolean allItemsTerminal(java.util.UUID manifestId) {
        return itemRepository.findByManifestId(manifestId).stream()
                .allMatch(i -> TERMINAL.contains(i.getStatus()));
    }
}
