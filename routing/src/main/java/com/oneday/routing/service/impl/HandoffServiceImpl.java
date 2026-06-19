package com.oneday.routing.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.routing.domain.DiscrepancyType;
import com.oneday.routing.domain.HandoffDirection;
import com.oneday.routing.domain.HandoffReconciliation;
import com.oneday.routing.domain.ManifestItemStatus;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.domain.VanManifestItem;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.repository.HandoffReconciliationRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.HandoffService;
import com.oneday.routing.service.model.StopReconciliation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reconciles one DA's exchange at a stop. Expected = manifest items filtered by stop_seq + da_id +
 * direction; actual = the scanned sets the driver reports. The set math yields MISSING (expected,
 * not scanned), EXTRA (scanned, not expected — mis-route), REJECTED (expected, refused). Each bucket
 * is persisted append-only and emitted as HANDOFF_DISCREPANCY; a fully clean DA emits HANDOFF_COMPLETED.
 */
@Service
class HandoffServiceImpl implements HandoffService {

    private static final Logger log = LoggerFactory.getLogger(HandoffServiceImpl.class);

    private final VanManifestRepository manifestRepository;
    private final VanManifestItemRepository itemRepository;
    private final HandoffReconciliationRepository reconciliationRepository;
    private final RoutePlanRepository planRepository;
    private final CronEventProducer cronEventProducer;
    private final ObjectMapper objectMapper;

    HandoffServiceImpl(VanManifestRepository manifestRepository, VanManifestItemRepository itemRepository,
                       HandoffReconciliationRepository reconciliationRepository, RoutePlanRepository planRepository,
                       CronEventProducer cronEventProducer, ObjectMapper objectMapper) {
        this.manifestRepository = manifestRepository;
        this.itemRepository = itemRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.planRepository = planRepository;
        this.cronEventProducer = cronEventProducer;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public StopReconciliation reconcileStop(UUID vanId, int loopIndex, LocalDate date, int stopSeq, UUID daId,
                                            Set<UUID> deliverScanned, Set<UUID> collectScanned, Set<UUID> rejected) {
        VanManifest manifest = manifestRepository.findByVanIdAndLoopIndexAndValidDate(vanId, loopIndex, date)
                .orElse(null);
        if (manifest == null) {
            log.warn("Reconcile stop: no manifest for van {} loop {} date {}", vanId, loopIndex, date);
            return StopReconciliation.noManifest(stopSeq, daId);
        }
        UUID cityId = cityOf(manifest);

        List<VanManifestItem> atStop = itemRepository.findByManifestIdAndStopSeq(manifest.getId(), stopSeq).stream()
                .filter(i -> daId.equals(i.getCounterpartyDaId()) && i.getStatus() != ManifestItemStatus.EXCEPTION)
                .toList();

        List<StopReconciliation.Discrepancy> discrepancies = new ArrayList<>();
        discrepancies.addAll(reconcileDirection(manifest, cityId, stopSeq, daId, HandoffDirection.DELIVER,
                atStop, deliverScanned, rejected));
        discrepancies.addAll(reconcileDirection(manifest, cityId, stopSeq, daId, HandoffDirection.COLLECT,
                atStop, collectScanned, Set.of()));

        boolean clean = discrepancies.isEmpty();
        if (clean) {
            cronEventProducer.emitHandoffCompleted(manifest.getId(), vanId, cityId, stopSeq, daId);
        }
        return new StopReconciliation(manifest.getId(), stopSeq, daId, clean, discrepancies);
    }

    // Reconcile one direction's expected items against what was scanned. Writes one reconciliation
    // row per discrepancy bucket (or a single NONE row when clean) and emits per-bucket discrepancy events.
    private List<StopReconciliation.Discrepancy> reconcileDirection(
            VanManifest manifest, UUID cityId, int stopSeq, UUID daId, HandoffDirection direction,
            List<VanManifestItem> atStop, Set<UUID> scanned, Set<UUID> rejected) {

        List<VanManifestItem> expected = atStop.stream().filter(i -> i.getDirection() == direction).toList();
        Set<UUID> expectedIds = expected.stream().map(VanManifestItem::getParcelId)
                .collect(java.util.stream.Collectors.toSet());

        List<UUID> missing = expected.stream()
                .filter(i -> !scanned.contains(i.getParcelId()) && !rejected.contains(i.getParcelId()))
                .map(VanManifestItem::getParcelId).toList();
        List<UUID> rejectedHere = expected.stream()
                .filter(i -> rejected.contains(i.getParcelId()))
                .map(VanManifestItem::getParcelId).toList();
        List<UUID> extra = scanned.stream().filter(id -> !expectedIds.contains(id)).toList();
        int matched = expected.size() - missing.size() - rejectedHere.size();

        if (missing.isEmpty() && rejectedHere.isEmpty() && extra.isEmpty()) {
            if (!expected.isEmpty()) {
                writeRow(manifest.getId(), stopSeq, daId, expected.size(), matched, DiscrepancyType.NONE, List.of(),
                        direction + " clean");
            }
            return List.of();
        }

        // Missing & rejected parcels fall out of this loop's custody — flag the items EXCEPTION.
        markException(expected, missing);
        markException(expected, rejectedHere);

        List<StopReconciliation.Discrepancy> out = new ArrayList<>();
        out.addAll(emitBucket(manifest, cityId, stopSeq, daId, direction, expected.size(), matched,
                DiscrepancyType.MISSING, missing));
        out.addAll(emitBucket(manifest, cityId, stopSeq, daId, direction, expected.size(), matched,
                DiscrepancyType.REJECTED, rejectedHere));
        out.addAll(emitBucket(manifest, cityId, stopSeq, daId, direction, expected.size(), matched,
                DiscrepancyType.EXTRA, extra));
        return out;
    }

    private List<StopReconciliation.Discrepancy> emitBucket(
            VanManifest manifest, UUID cityId, int stopSeq, UUID daId, HandoffDirection direction,
            int expectedCount, int actualCount, DiscrepancyType type, List<UUID> parcelIds) {
        if (parcelIds.isEmpty()) return List.of();
        writeRow(manifest.getId(), stopSeq, daId, expectedCount, actualCount, type, parcelIds,
                direction + " " + type);
        cronEventProducer.emitHandoffDiscrepancy(manifest.getId(), manifest.getVanId(), cityId, stopSeq, daId,
                type, parcelIds);
        return List.of(new StopReconciliation.Discrepancy(direction, type, parcelIds));
    }

    private void markException(List<VanManifestItem> expected, List<UUID> parcelIds) {
        expected.stream().filter(i -> parcelIds.contains(i.getParcelId())).forEach(i -> {
            i.setStatus(ManifestItemStatus.EXCEPTION);
            itemRepository.save(i);
        });
    }

    private void writeRow(UUID manifestId, int stopSeq, UUID daId, int expected, int actual,
                          DiscrepancyType type, List<UUID> parcelIds, String reason) {
        reconciliationRepository.save(HandoffReconciliation.builder()
                .manifestId(manifestId)
                .stopSeq(stopSeq)
                .daId(daId)
                .expectedCount(expected)
                .actualCount(actual)
                .discrepancyType(type)
                .discrepancyParcelIds(toJson(parcelIds))
                .reason(reason)
                .build());
    }

    private UUID cityOf(VanManifest manifest) {
        return planRepository.findById(manifest.getRoutePlanId()).map(RoutePlan::getCityId).orElse(null);
    }

    private String toJson(List<UUID> ids) {
        try {
            return objectMapper.writeValueAsString(ids.stream().map(UUID::toString).toList());
        } catch (Exception e) {
            log.warn("Could not serialize discrepancy parcel ids {}: {}", ids, e.getMessage());
            return "[]";
        }
    }
}
