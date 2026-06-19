package com.oneday.routing.service.impl;

import com.oneday.routing.domain.HandoffDirection;
import com.oneday.routing.domain.ManifestItemStatus;
import com.oneday.routing.domain.ManifestStatus;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.domain.VanManifestItem;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.RecoveryService;
import com.oneday.routing.service.VanManifestService;
import com.oneday.routing.service.model.RecoverySummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Failure recovery (§13.4/§13.5). Breakdown: the recovery van inherits the broken van's open items
 * (custody re-pointed to fresh recovery-van manifests, append-only — the broken van's rows are left
 * as the historical record). No-show: undelivered deliveries are carried to the DA's next loop via
 * {@link VanManifestService#rebindDelivery}; collections are deferred to the next pickup-driven bind.
 */
@Service
class RecoveryServiceImpl implements RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryServiceImpl.class);

    private static final List<ManifestItemStatus> TERMINAL =
            List.of(ManifestItemStatus.RECONCILED, ManifestItemStatus.HANDED_OFF, ManifestItemStatus.EXCEPTION);

    private final VanManifestRepository manifestRepository;
    private final VanManifestItemRepository itemRepository;
    private final VanManifestService manifestService;
    private final CronEventProducer cronEventProducer;
    private final Clock clock;

    RecoveryServiceImpl(VanManifestRepository manifestRepository, VanManifestItemRepository itemRepository,
                        VanManifestService manifestService, CronEventProducer cronEventProducer, Clock clock) {
        this.manifestRepository = manifestRepository;
        this.itemRepository = itemRepository;
        this.manifestService = manifestService;
        this.cronEventProducer = cronEventProducer;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RecoverySummary recoverVan(UUID brokenVanId, UUID recoveryVanId, UUID cityId, LocalDate date,
                                      double lastLat, double lastLon) {
        List<VanManifest> manifests = manifestRepository.findByVanIdAndValidDate(brokenVanId, date);
        UUID routePlanId = manifests.isEmpty() ? null : manifests.get(0).getRoutePlanId();
        cronEventProducer.emitVanBreakdown(brokenVanId, cityId, routePlanId, lastLat, lastLon, Instant.now(clock));

        int reassigned = 0;
        for (VanManifest broken : manifests) {
            List<VanManifestItem> open = itemRepository.findByManifestId(broken.getId()).stream()
                    .filter(i -> !TERMINAL.contains(i.getStatus()))
                    .toList();
            if (open.isEmpty()) continue;
            VanManifest recovery = recoveryManifestFor(broken, recoveryVanId, date);
            for (VanManifestItem item : open) {
                item.setManifestId(recovery.getId());
                itemRepository.save(item);
                reassigned++;
            }
        }
        log.info("Recovery van {} took over {} open items from broken van {} ({})",
                recoveryVanId, reassigned, brokenVanId, date);
        return new RecoverySummary(brokenVanId, recoveryVanId, reassigned, 0);
    }

    @Override
    @Transactional
    public int carryNoShow(UUID vanId, int loopIndex, LocalDate date, int stopSeq, UUID daId) {
        VanManifest manifest = manifestRepository.findByVanIdAndLoopIndexAndValidDate(vanId, loopIndex, date)
                .orElse(null);
        if (manifest == null) return 0;
        // Deliveries still on the van (PLANNED/LOADED) for this DA & stop — carry to the next loop.
        List<VanManifestItem> undelivered = itemRepository.findByManifestIdAndStopSeq(manifest.getId(), stopSeq).stream()
                .filter(i -> i.getDirection() == HandoffDirection.DELIVER && daId.equals(i.getCounterpartyDaId()))
                .filter(i -> i.getStatus() == ManifestItemStatus.PLANNED || i.getStatus() == ManifestItemStatus.LOADED)
                .toList();
        int carried = 0;
        for (VanManifestItem item : undelivered) {
            manifestService.rebindDelivery(item.getParcelId());
            carried++;
        }
        log.info("DA {} no-show at van {} loop {} stop {}: carried {} deliveries to next loop",
                daId, vanId, loopIndex, stopSeq, carried);
        return carried;
    }

    // Find or create the recovery van's manifest for the same loop/day (race-safe via UNIQUE constraint).
    private VanManifest recoveryManifestFor(VanManifest broken, UUID recoveryVanId, LocalDate date) {
        return manifestRepository.findByVanIdAndLoopIndexAndValidDate(recoveryVanId, broken.getLoopIndex(), date)
                .orElseGet(() -> {
                    try {
                        return manifestRepository.saveAndFlush(VanManifest.builder()
                                .routePlanId(broken.getRoutePlanId())
                                .vanId(recoveryVanId)
                                .loopIndex(broken.getLoopIndex())
                                .validDate(date)
                                .status(ManifestStatus.IN_PROGRESS)
                                .build());
                    } catch (DataIntegrityViolationException raced) {
                        return manifestRepository.findByVanIdAndLoopIndexAndValidDate(recoveryVanId, broken.getLoopIndex(), date)
                                .orElseThrow(() -> new IllegalStateException("recovery manifest vanished for van=" + recoveryVanId));
                    }
                });
    }
}
