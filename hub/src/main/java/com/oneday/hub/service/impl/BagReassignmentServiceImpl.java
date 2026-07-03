package com.oneday.hub.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.hub.domain.*;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.*;
import com.oneday.hub.service.BagReassignmentService;
import com.oneday.hub.service.FlightBagService;
import com.oneday.hub.service.exception.NothingToReassignException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Executes M9's flight reassignments (§9). Pure mechanics — no flight choice, no SLA gate (M7-D-006).
 * Movement is append-only: a source item flips to REMOVED and a fresh item is added to the target bag.
 */
@Service
class BagReassignmentServiceImpl implements BagReassignmentService {

    private final FlightBagService flightBagService;
    private final FlightBagRepository flightBagRepository;
    private final FlightBagItemRepository flightBagItemRepository;
    private final BagManifestRepository bagManifestRepository;
    private final StandRepository standRepository;
    private final HubEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    BagReassignmentServiceImpl(FlightBagService flightBagService,
                               FlightBagRepository flightBagRepository,
                               FlightBagItemRepository flightBagItemRepository,
                               BagManifestRepository bagManifestRepository,
                               StandRepository standRepository,
                               HubEventProducer eventProducer,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.flightBagService = flightBagService;
        this.flightBagRepository = flightBagRepository;
        this.flightBagItemRepository = flightBagItemRepository;
        this.bagManifestRepository = bagManifestRepository;
        this.standRepository = standRepository;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ReassignResult reassign(FlightReassignmentCommand cmd) {
        List<FlightBagItem> sourceItems = resolveSourceItems(cmd);
        if (sourceItems.isEmpty()) {
            throw new NothingToReassignException(
                    "from-flight=" + cmd.fromFlightNo() + " parcelIds=" + cmd.parcelIds());
        }

        // The target flight's open bag (lazy create → allocates a stand, emits BAG_CREATED on first open).
        FlightBagItem template = sourceItems.get(0);
        FlightBag sourceTemplateBag = requireBag(template.getBagId());
        FlightBag target = flightBagService.openBag(new FlightBagService.OpenBagCommand(
                sourceTemplateBag.getCityId(), sourceTemplateBag.getHubId(),
                cmd.toFlightNo(), cmd.toFlightDate(), sourceTemplateBag.getOriginHub(),
                cmd.destHub(), cmd.newCutoff()));

        Set<UUID> affectedSourceBagIds = new LinkedHashSet<>();
        UUID supersedesManifestId = null;
        int moved = 0;
        for (FlightBagItem item : sourceItems) {
            if (item.getBagId().equals(target.getId())) {
                continue;   // already on the target flight — nothing to move
            }
            FlightBag source = requireBag(item.getBagId());
            if (supersedesManifestId == null) {
                supersedesManifestId = source.getManifestId();   // link the new manifest to the one it supersedes
            }
            removeFromSource(item, source);
            flightBagService.addParcel(target.getId(), item.getShipmentRef());   // append-only: fresh item, re-reads weight
            affectedSourceBagIds.add(source.getId());
            moved++;
        }

        if (moved == 0) {
            return new ReassignResult(target, 0, target.getManifestId(), standNo(target.getCurrentStandId()));
        }

        cancelEmptiedSources(affectedSourceBagIds);

        // openBag/addParcel mutate the target in the same tx; re-read for the fresh counts.
        FlightBag freshTarget = requireBag(target.getId());
        BagManifest manifest = regenerateManifest(freshTarget, supersedesManifestId);
        freshTarget.setManifestId(manifest.getId());
        flightBagRepository.save(freshTarget);

        String standNo = standNo(freshTarget.getCurrentStandId());
        String fromFlightNo = cmd.fromFlightNo() != null ? cmd.fromFlightNo() : sourceTemplateBag.getFlightNo();
        eventProducer.emitBagRescheduled(freshTarget, fromFlightNo, cmd.reason().name(), moved, standNo, manifest.getId());
        return new ReassignResult(freshTarget, moved, manifest.getId(), standNo);
    }

    /** parcelIds → those bagged items; else the whole open from-flight bag's items. */
    private List<FlightBagItem> resolveSourceItems(FlightReassignmentCommand cmd) {
        if (cmd.parcelIds() != null && !cmd.parcelIds().isEmpty()) {
            List<FlightBagItem> items = new ArrayList<>();
            for (UUID parcelId : cmd.parcelIds()) {
                flightBagItemRepository.findFirstByParcelIdAndStatus(parcelId, FlightBagItemStatus.IN_BAG)
                        .ifPresent(items::add);
            }
            return items;
        }
        if (cmd.fromFlightNo() == null) {
            return List.of();
        }
        return flightBagRepository
                .findFirstByFlightNoAndDestHubAndStatusInOrderByCreatedAtDesc(cmd.fromFlightNo(), cmd.destHub(),
                        List.of(FlightBagStatus.OPEN, FlightBagStatus.SEALED))
                .map(bag -> flightBagItemRepository.findByBagIdAndStatus(bag.getId(), FlightBagItemStatus.IN_BAG))
                .orElseGet(List::of);
    }

    private void removeFromSource(FlightBagItem item, FlightBag source) {
        item.setStatus(FlightBagItemStatus.REMOVED);
        item.setRemovedAt(clock.instant());
        flightBagItemRepository.save(item);
        source.setParcelCount(Math.max(0, source.getParcelCount() - 1));
        source.setWeightGrams(Math.max(0, source.getWeightGrams() - item.getWeightGrams()));
        flightBagRepository.save(source);
    }

    /** An emptied source bag frees its stand (§9) — CANCELLED so findFreeStands releases it. */
    private void cancelEmptiedSources(Set<UUID> sourceBagIds) {
        for (UUID id : sourceBagIds) {
            FlightBag source = requireBag(id);
            if (source.getParcelCount() == 0 && source.getStatus() != FlightBagStatus.DISPATCHED
                    && source.getStatus() != FlightBagStatus.HANDED_OVER) {
                source.setStatus(FlightBagStatus.CANCELLED);
                flightBagRepository.save(source);
            }
        }
    }

    /** Append-only superseding manifest for the target's current contents (§9, M7-D-008). */
    private BagManifest regenerateManifest(FlightBag target, UUID supersedesId) {
        List<FlightBagItem> items = flightBagItemRepository.findByBagIdAndStatus(target.getId(), FlightBagItemStatus.IN_BAG);
        return bagManifestRepository.save(BagManifest.builder()
                .bagId(target.getId())
                .direction(SortDirection.OUTBOUND)
                .manifestKind(ManifestKind.FLIGHT)
                .flightNo(target.getFlightNo())
                .parcelCount(items.size())
                .weightGrams(items.stream().mapToInt(FlightBagItem::getWeightGrams).sum())
                .parcels(toParcelsJson(items))
                .supersedesId(supersedesId)
                .build());
    }

    private FlightBag requireBag(UUID bagId) {
        return flightBagRepository.findById(bagId)
                .orElseThrow(() -> new com.oneday.hub.service.exception.BagNotFoundException(bagId));
    }

    private String standNo(UUID standId) {
        return standRepository.findById(standId).map(Stand::getStandNo).orElse(null);
    }

    private String toParcelsJson(List<FlightBagItem> items) {
        List<ManifestParcel> rows = items.stream()
                .map(i -> new ManifestParcel(i.getParcelId(), i.getShipmentRef(), i.getWeightGrams()))
                .toList();
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize reschedule manifest parcels", e);
        }
    }

    private record ManifestParcel(UUID parcelId, String shipmentRef, int weightGrams) {
    }
}
