package com.oneday.hub.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.hub.domain.*;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.BagManifestRepository;
import com.oneday.hub.repository.DeliveryBagItemRepository;
import com.oneday.hub.repository.DeliveryBagRepository;
import com.oneday.hub.repository.StandRepository;
import com.oneday.hub.service.DeliveryBagService;
import com.oneday.hub.service.exception.BagNotFoundException;
import com.oneday.hub.service.exception.DuplicateBagItemException;
import com.oneday.hub.service.exception.IllegalBagStateException;
import com.oneday.hub.service.exception.NoFreeStandException;
import com.oneday.hub.service.port.ShipmentInfoPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Implements the delivery-bag lifecycle (§8.1), the inbound mirror of {@link BagServiceImpl}. */
@Service
class DeliveryBagServiceImpl implements DeliveryBagService {

    private static final String DELIVERY_DOCK = "DELIVERY_DOCK";

    private final DeliveryBagRepository deliveryBagRepository;
    private final DeliveryBagItemRepository deliveryBagItemRepository;
    private final BagManifestRepository bagManifestRepository;
    private final StandRepository standRepository;
    private final HubEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    DeliveryBagServiceImpl(DeliveryBagRepository deliveryBagRepository,
                           DeliveryBagItemRepository deliveryBagItemRepository,
                           BagManifestRepository bagManifestRepository,
                           StandRepository standRepository,
                           HubEventProducer eventProducer,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.deliveryBagRepository = deliveryBagRepository;
        this.deliveryBagItemRepository = deliveryBagItemRepository;
        this.bagManifestRepository = bagManifestRepository;
        this.standRepository = standRepository;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DeliveryBag openBag(OpenDeliveryBagCommand cmd) {
        // Lazy create: one open bag per key, scoped by kind (the dest mirror of the flight-bag key).
        return findOpenBag(cmd).orElseGet(() -> createBag(cmd));
    }

    private Optional<DeliveryBag> findOpenBag(OpenDeliveryBagCommand cmd) {
        return switch (cmd.bagKind()) {
            case ROUTE -> deliveryBagRepository
                    .findByLoopIdAndBagDateAndStatus(cmd.loopId(), cmd.bagDate(), DeliveryBagStatus.OPEN);
            case DA_TERRITORY -> deliveryBagRepository
                    .findByDaTerritoryIdAndBagDateAndStatus(cmd.daTerritoryId(), cmd.bagDate(), DeliveryBagStatus.OPEN);
            case ZONE -> deliveryBagRepository
                    .findByZoneIdAndBagDateAndStatus(cmd.zoneId(), cmd.bagDate(), DeliveryBagStatus.OPEN);
        };
    }

    private DeliveryBag createBag(OpenDeliveryBagCommand cmd) {
        // Dynamic stand assignment from the shared pool, preferring delivery-dock shelves (soft hint).
        Stand stand = standRepository
                .findFreeStands(cmd.hubId(), StandStatus.OPEN, DELIVERY_DOCK)
                .stream().findFirst()
                .orElseThrow(() -> new NoFreeStandException(cmd.hubId()));

        DeliveryBag bag = deliveryBagRepository.save(DeliveryBag.builder()
                .cityId(cmd.cityId())
                .hubId(cmd.hubId())
                .bagKind(cmd.bagKind())
                .bagDate(cmd.bagDate())
                .routePlanId(cmd.routePlanId())
                .loopId(cmd.loopId())
                .daTerritoryId(cmd.daTerritoryId())
                .zoneId(cmd.zoneId())
                .currentStandId(stand.getId())
                .status(DeliveryBagStatus.OPEN)
                .parcelCount(0)
                .weightGrams(0)
                .build());
        eventProducer.emitDeliveryBagCreated(bag, stand.getStandNo());
        return bag;
    }

    @Override
    @Transactional
    public DeliveryBagItem addParcel(UUID deliveryBagId, ShipmentInfoPort.ParcelInfo parcel,
                                     UUID destHexId, UUID daTerritoryId, UUID routePlanId) {
        DeliveryBag bag = requireBag(deliveryBagId);
        if (bag.getStatus() != DeliveryBagStatus.OPEN) {
            throw new IllegalBagStateException("Delivery bag " + deliveryBagId + " is " + bag.getStatus() + ", not OPEN");
        }
        if (deliveryBagItemRepository.existsByParcelIdAndStatusIn(parcel.shipmentId(),
                List.of(DeliveryBagItemStatus.STAGED, DeliveryBagItemStatus.LOADED))) {
            throw new DuplicateBagItemException(parcel.shipmentRef());
        }

        int weight = parcel.chargeableWeightGrams();
        DeliveryBagItem item = deliveryBagItemRepository.save(DeliveryBagItem.builder()
                .parcelId(parcel.shipmentId())
                .shipmentRef(parcel.shipmentRef())
                .cityId(bag.getCityId())
                .hubId(bag.getHubId())
                .destHexId(destHexId)
                .standId(bag.getCurrentStandId())
                .deliveryBagId(deliveryBagId)
                .daTerritoryId(daTerritoryId)
                .routePlanId(routePlanId)
                .dropType(parcel.dropType())
                .status(DeliveryBagItemStatus.STAGED)
                .build());

        bag.setParcelCount(bag.getParcelCount() + 1);
        bag.setWeightGrams(bag.getWeightGrams() + weight);
        deliveryBagRepository.save(bag);
        return item;
    }

    @Override
    @Transactional
    public SealResult seal(UUID deliveryBagId) {
        DeliveryBag bag = requireBag(deliveryBagId);
        if (bag.getStatus() != DeliveryBagStatus.OPEN) {
            throw new IllegalBagStateException("Delivery bag " + deliveryBagId + " is already " + bag.getStatus());
        }
        List<DeliveryBagItem> items = deliveryBagItemRepository
                .findByDeliveryBagIdAndStatus(deliveryBagId, DeliveryBagItemStatus.STAGED);

        BagManifest manifest = bagManifestRepository.save(BagManifest.builder()
                .bagId(deliveryBagId)
                .direction(SortDirection.INBOUND)
                .manifestKind(ManifestKind.LOAD_LIST)
                .parcelCount(items.size())
                .weightGrams(bag.getWeightGrams())   // per-item weight isn't kept on the item; the bag carries the running total
                .parcels(toParcelsJson(items))
                .build());

        bag.setStatus(DeliveryBagStatus.SEALED);
        bag.setSealedAt(clock.instant());
        bag.setManifestId(manifest.getId());
        deliveryBagRepository.save(bag);

        String standNo = standRepository.findById(bag.getCurrentStandId()).map(Stand::getStandNo).orElse(null);
        eventProducer.emitDeliveryBagSealed(bag, standNo);
        return new SealResult(bag, manifest);
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryBag bag(UUID deliveryBagId) {
        return requireBag(deliveryBagId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryBag> deliveryBags(UUID hubId, java.time.LocalDate date) {
        return deliveryBagRepository.findByHubIdAndBagDate(hubId, date);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryBagItem> staging(UUID cityId, DeliveryBagItemStatus status) {
        return deliveryBagItemRepository.findByCityIdAndStatus(cityId, status);
    }

    private DeliveryBag requireBag(UUID deliveryBagId) {
        return deliveryBagRepository.findById(deliveryBagId).orElseThrow(() -> new BagNotFoundException(deliveryBagId));
    }

    private String toParcelsJson(List<DeliveryBagItem> items) {
        List<LoadListParcel> rows = items.stream()
                .map(i -> new LoadListParcel(i.getParcelId(), i.getShipmentRef(), i.getDestHexId()))
                .toList();
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize delivery load list", e);
        }
    }

    private record LoadListParcel(UUID parcelId, String shipmentRef, UUID destHexId) {
    }
}
