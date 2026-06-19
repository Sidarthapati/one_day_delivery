package com.oneday.routing.api;

import com.oneday.routing.domain.ManifestStatus;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.domain.VanManifestItem;
import com.oneday.routing.dto.LoadScanRequest;
import com.oneday.routing.dto.StopConfirmRequest;
import com.oneday.routing.dto.VanLiveStatusResponse;
import com.oneday.routing.dto.VanManifestResponse;
import com.oneday.routing.repository.VanLiveStatusRepository;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.CustodyService;
import com.oneday.routing.service.HandoffService;
import com.oneday.routing.service.model.StopReconciliation;
import com.oneday.routing.service.model.VanCustodyCommand;
import com.oneday.routing.service.port.ScanLedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The van-driver app's contract (§15, M6-D-020), parallel to M5's DA app. A thin REST surface over
 * the manifest + custody services: load at the hub, confirm each stop's per-DA exchange, return at
 * the hub. Plus the ops live-map read. High-frequency GPS lives on {@link VanTelemetryController};
 * this carries the deliberate driver actions.
 */
@RestController
@RequestMapping("/routing/vans")
public class VanDriverController {

    private static final Logger log = LoggerFactory.getLogger(VanDriverController.class);

    private final VanManifestRepository manifestRepository;
    private final VanManifestItemRepository itemRepository;
    private final VanLiveStatusRepository liveRepository;
    private final CustodyService custodyService;
    private final HandoffService handoffService;
    private final Clock clock;

    VanDriverController(VanManifestRepository manifestRepository, VanManifestItemRepository itemRepository,
                        VanLiveStatusRepository liveRepository, CustodyService custodyService,
                        HandoffService handoffService, Clock clock) {
        this.manifestRepository = manifestRepository;
        this.itemRepository = itemRepository;
        this.liveRepository = liveRepository;
        this.custodyService = custodyService;
        this.handoffService = handoffService;
        this.clock = clock;
    }

    /** §15 step 1 — the loop's load/collect manifest the app drives. */
    @GetMapping("/{vanId}/manifest")
    public ResponseEntity<VanManifestResponse> manifest(@PathVariable UUID vanId,
                                                        @RequestParam("loop") int loop,
                                                        @RequestParam(value = "date", required = false) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(clock);
        VanManifest manifest = manifestRepository.findByVanIdAndLoopIndexAndValidDate(vanId, loop, day).orElse(null);
        if (manifest == null) {
            return ResponseEntity.notFound().build();
        }
        List<VanManifestItem> items = itemRepository.findByManifestId(manifest.getId());
        return ResponseEntity.ok(VanManifestResponse.from(manifest, items));
    }

    /** §15 step 2 — scan each parcel onto the van at the hub (VAN_LOAD); custody seals it LOADED. */
    @PostMapping("/{vanId}/load-scan")
    public List<String> loadScan(@PathVariable UUID vanId, @RequestBody LoadScanRequest request) {
        return recordHubScans(vanId, request, ScanLedgerPort.VanScanType.VAN_LOAD);
    }

    /** §15 step 7 — scan collected parcels off the van at the hub (VAN_UNLOAD); manifest → RETURNED. */
    @PostMapping("/{vanId}/return-scan")
    public List<String> returnScan(@PathVariable UUID vanId, @RequestBody LoadScanRequest request) {
        LocalDate day = request.dateOrToday(LocalDate.now(clock));
        markReturned(vanId, request.loopIndex(), day);
        return recordHubScans(vanId, request, ScanLedgerPort.VanScanType.VAN_UNLOAD);
    }

    /** §15 step 5 "Confirm stop" — reconcile one DA's exchange (expected vs scanned). */
    @PostMapping("/{vanId}/stops/confirm")
    public StopReconciliation confirmStop(@PathVariable UUID vanId, @RequestBody StopConfirmRequest request) {
        LocalDate day = request.dateOrToday(LocalDate.now(clock));
        return handoffService.reconcileStop(vanId, request.loopIndex(), day, request.stopSeq(), request.daId(),
                request.deliverSet(), request.collectSet(), request.rejectedSet());
    }

    /** Ops live map — every van's latest position + lateness in a city. */
    @GetMapping("/{cityId}/live")
    public List<VanLiveStatusResponse> live(@PathVariable UUID cityId) {
        return liveRepository.findByCityId(cityId).stream().map(VanLiveStatusResponse::from).toList();
    }

    private List<String> recordHubScans(UUID vanId, LoadScanRequest request, ScanLedgerPort.VanScanType type) {
        Instant ts = clock.instant();
        return request.parcelIdsOrEmpty().stream()
                .map(parcelId -> custodyService.record(new VanCustodyCommand(
                        parcelId, type, vanId, request.driverId(), null, ts)))
                .map(r -> r.parcelId() + ":" + r.status().name())
                .toList();
    }

    // Returning to the hub flips the loop's manifest IN_PROGRESS → RETURNED; the final unload scan then
    // completes it to RECONCILED (CustodyService) once every item is terminal.
    private void markReturned(UUID vanId, Integer loopIndex, LocalDate day) {
        if (loopIndex == null) return;
        manifestRepository.findByVanIdAndLoopIndexAndValidDate(vanId, loopIndex, day).ifPresent(m -> {
            if (m.getStatus() == ManifestStatus.IN_PROGRESS || m.getStatus() == ManifestStatus.LOADED) {
                m.setStatus(ManifestStatus.RETURNED);
                m.setReturnedAt(clock.instant());
                manifestRepository.save(m);
            }
        });
    }
}
