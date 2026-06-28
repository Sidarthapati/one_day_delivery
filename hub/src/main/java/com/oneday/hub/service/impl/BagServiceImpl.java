package com.oneday.hub.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.hub.domain.*;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.*;
import com.oneday.hub.service.BagService;
import com.oneday.hub.service.exception.*;
import com.oneday.hub.service.port.BarcodePort;
import com.oneday.hub.service.port.ShipmentInfoPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Implements the flight-bag lifecycle (§7.2–7.3). All time via the injected IST {@link Clock}. */
@Service
class BagServiceImpl implements BagService {

    private final FlightBagRepository flightBagRepository;
    private final BagItemRepository bagItemRepository;
    private final BagManifestRepository bagManifestRepository;
    private final StandRepository standRepository;
    private final StandReassignmentAuditRepository reassignmentAuditRepository;
    private final ShipmentInfoPort shipmentInfoPort;
    private final BarcodePort barcodePort;
    private final HubEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    BagServiceImpl(FlightBagRepository flightBagRepository,
                   BagItemRepository bagItemRepository,
                   BagManifestRepository bagManifestRepository,
                   StandRepository standRepository,
                   StandReassignmentAuditRepository reassignmentAuditRepository,
                   ShipmentInfoPort shipmentInfoPort,
                   BarcodePort barcodePort,
                   HubEventProducer eventProducer,
                   ObjectMapper objectMapper,
                   Clock clock) {
        this.flightBagRepository = flightBagRepository;
        this.bagItemRepository = bagItemRepository;
        this.bagManifestRepository = bagManifestRepository;
        this.standRepository = standRepository;
        this.reassignmentAuditRepository = reassignmentAuditRepository;
        this.shipmentInfoPort = shipmentInfoPort;
        this.barcodePort = barcodePort;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public FlightBag openBag(OpenBagCommand cmd) {
        // Lazy create: one open bag per (flight, date, dest_hub) (C6). Idempotent re-open returns it.
        return flightBagRepository
                .findByFlightNoAndFlightDateAndDestHubAndStatus(
                        cmd.flightNo(), cmd.flightDate(), cmd.destHub(), BagStatus.OPEN)
                .orElseGet(() -> createBag(cmd));
    }

    private FlightBag createBag(OpenBagCommand cmd) {
        // Dynamic stand assignment: grab the next free shelf from the hub's shared stand pool. The
        // stand becomes "this flight's bag" only now — nothing is pre-mapped. Freed again on dispatch.
        Stand stand = standRepository
                .findFreeStands(cmd.hubId(), StandStatus.OPEN, "AIRPORT_DOCK")
                .stream().findFirst()
                .orElseThrow(() -> new NoFreeStandException(cmd.hubId()));

        FlightBag bag = flightBagRepository.save(FlightBag.builder()
                .cityId(cmd.cityId())
                .hubId(cmd.hubId())
                .flightNo(cmd.flightNo())
                .flightDate(cmd.flightDate())
                .originHub(cmd.originHub())
                .destHub(cmd.destHub())
                .currentStandId(stand.getId())
                .status(BagStatus.OPEN)
                .parcelCount(0)
                .weightGrams(0)
                .bagCutoff(cmd.bagCutoff())
                .build());
        eventProducer.emitBagCreated(bag, stand.getStandNo());
        return bag;
    }

    @Override
    @Transactional
    public BagItem addParcel(UUID bagId, String shipmentRef) {
        FlightBag bag = requireBag(bagId);
        if (bag.getStatus() != BagStatus.OPEN) {
            throw new IllegalBagStateException("Bag " + bagId + " is " + bag.getStatus() + ", not OPEN");
        }
        ShipmentInfoPort.ParcelInfo parcel = shipmentInfoPort.lookup(shipmentRef)
                .orElseThrow(() -> new ParcelNotFoundException(shipmentRef));
        if (bagItemRepository.existsByParcelIdAndStatus(parcel.shipmentId(), BagItemStatus.IN_BAG)) {
            throw new DuplicateBagItemException(shipmentRef);
        }

        int weight = parcel.chargeableWeightGrams();
        BagItem item = bagItemRepository.save(BagItem.builder()
                .bagId(bagId)
                .parcelId(parcel.shipmentId())
                .shipmentRef(parcel.shipmentRef())
                .weightGrams(weight)
                .status(BagItemStatus.IN_BAG)
                .build());

        bag.setParcelCount(bag.getParcelCount() + 1);
        bag.setWeightGrams(bag.getWeightGrams() + weight);
        flightBagRepository.save(bag);
        return item;
    }

    @Override
    @Transactional
    public FlightBag reassignStand(UUID bagId, UUID newStandId, UUID actorId, String reason) {
        FlightBag bag = requireBag(bagId);
        if (bag.getStatus() != BagStatus.OPEN) {
            throw new IllegalBagStateException("Cannot reassign a " + bag.getStatus() + " bag");
        }
        Stand newStand = standRepository.findById(newStandId)
                .orElseThrow(() -> new StandNotFoundException(newStandId));

        String newLabel = barcodePort.buildBagLabel(bag.getFlightNo(), newStand.getStandNo());
        reassignmentAuditRepository.save(StandReassignmentAudit.builder()
                .bagId(bagId)
                .oldStandId(bag.getCurrentStandId())
                .newStandId(newStandId)
                .actorId(actorId)
                .reason(reason)
                .newLabel(newLabel)
                .build());

        bag.setCurrentStandId(newStandId);   // append-only history in the audit; pointer moves (M7-D-008)
        return flightBagRepository.save(bag);
    }

    @Override
    @Transactional
    public SealResult seal(UUID bagId) {
        FlightBag bag = requireBag(bagId);
        if (bag.getStatus() != BagStatus.OPEN) {
            throw new IllegalBagStateException("Bag " + bagId + " is already " + bag.getStatus());
        }
        List<BagItem> items = bagItemRepository.findByBagIdAndStatus(bagId, BagItemStatus.IN_BAG);

        BagManifest manifest = bagManifestRepository.save(BagManifest.builder()
                .bagId(bagId)
                .direction(SortDirection.OUTBOUND)
                .manifestKind(ManifestKind.FLIGHT)
                .flightNo(bag.getFlightNo())
                .parcelCount(items.size())
                .weightGrams(items.stream().mapToInt(BagItem::getWeightGrams).sum())
                .parcels(toParcelsJson(items))
                .build());

        bag.setStatus(BagStatus.SEALED);
        bag.setSealedAt(clock.instant());
        bag.setManifestId(manifest.getId());
        flightBagRepository.save(bag);

        String standNo = standNo(bag.getCurrentStandId());
        eventProducer.emitBagSealed(bag, standNo);
        eventProducer.emitManifestGenerated(bag, manifest);
        return new SealResult(bag, manifest);
    }

    @Override
    @Transactional
    public FlightBag dispatch(UUID bagId) {
        FlightBag bag = requireBag(bagId);
        if (bag.getStatus() != BagStatus.SEALED) {
            throw new IllegalBagStateException("Bag " + bagId + " must be SEALED to dispatch, is " + bag.getStatus());
        }
        bag.setStatus(BagStatus.DISPATCHED);
        bag.setDispatchedAt(clock.instant());
        return flightBagRepository.save(bag);
    }

    @Override
    @Transactional(readOnly = true)
    public BagManifest currentManifest(UUID bagId) {
        return bagManifestRepository.findFirstByBagIdOrderByGeneratedAtDesc(bagId)
                .orElseThrow(() -> new IllegalBagStateException("No manifest for bag " + bagId + " (seal it first)"));
    }

    @Override
    @Transactional(readOnly = true)
    public FlightBag bag(UUID bagId) {
        return requireBag(bagId);
    }

    private FlightBag requireBag(UUID bagId) {
        return flightBagRepository.findById(bagId).orElseThrow(() -> new BagNotFoundException(bagId));
    }

    private String standNo(UUID standId) {
        return standRepository.findById(standId).map(Stand::getStandNo).orElse(null);
    }

    private String toParcelsJson(List<BagItem> items) {
        List<ManifestParcel> rows = items.stream()
                .map(i -> new ManifestParcel(i.getParcelId(), i.getShipmentRef(), i.getWeightGrams()))
                .toList();
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize bag manifest parcels", e);
        }
    }

    private record ManifestParcel(UUID parcelId, String shipmentRef, int weightGrams) {
    }
}
