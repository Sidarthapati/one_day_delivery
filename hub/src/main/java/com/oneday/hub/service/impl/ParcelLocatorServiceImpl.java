package com.oneday.hub.service.impl;

import com.oneday.hub.domain.DeliveryBag;
import com.oneday.hub.domain.DeliveryBagItem;
import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.domain.FlightBagItem;
import com.oneday.hub.domain.FlightBagItemStatus;
import com.oneday.hub.domain.Stand;
import com.oneday.hub.repository.DeliveryBagItemRepository;
import com.oneday.hub.repository.DeliveryBagRepository;
import com.oneday.hub.repository.FlightBagItemRepository;
import com.oneday.hub.repository.FlightBagRepository;
import com.oneday.hub.repository.StandRepository;
import com.oneday.hub.service.ParcelLocatorService;
import com.oneday.hub.service.exception.ParcelNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Reads the live bag a parcel sits in to report its stand (§14.2). */
@Service
class ParcelLocatorServiceImpl implements ParcelLocatorService {

    private final FlightBagItemRepository flightBagItemRepository;
    private final FlightBagRepository flightBagRepository;
    private final DeliveryBagItemRepository deliveryBagItemRepository;
    private final DeliveryBagRepository deliveryBagRepository;
    private final StandRepository standRepository;

    ParcelLocatorServiceImpl(FlightBagItemRepository flightBagItemRepository,
                             FlightBagRepository flightBagRepository,
                             DeliveryBagItemRepository deliveryBagItemRepository,
                             DeliveryBagRepository deliveryBagRepository,
                             StandRepository standRepository) {
        this.flightBagItemRepository = flightBagItemRepository;
        this.flightBagRepository = flightBagRepository;
        this.deliveryBagItemRepository = deliveryBagItemRepository;
        this.deliveryBagRepository = deliveryBagRepository;
        this.standRepository = standRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ParcelLocation locate(UUID parcelId) {
        FlightBagItem flightItem = flightBagItemRepository
                .findFirstByParcelIdAndStatus(parcelId, FlightBagItemStatus.IN_BAG).orElse(null);
        if (flightItem != null) {
            FlightBag bag = flightBagRepository.findById(flightItem.getBagId()).orElse(null);
            if (bag != null) {
                return new ParcelLocation(parcelId, "OUTBOUND", bag.getId(), bag.getCurrentStandId(),
                        standNo(bag.getCurrentStandId()), bag.getFlightNo(), null, bag.getStatus().name());
            }
        }

        DeliveryBagItem deliveryItem = deliveryBagItemRepository.findFirstByParcelId(parcelId).orElse(null);
        if (deliveryItem != null) {
            if (deliveryItem.getDeliveryBagId() == null) {   // hub-collect shelf placement — no bag
                return new ParcelLocation(parcelId, "SHELF", null, null, null, null, null,
                        deliveryItem.getStatus().name());
            }
            DeliveryBag bag = deliveryBagRepository.findById(deliveryItem.getDeliveryBagId()).orElse(null);
            if (bag != null) {
                return new ParcelLocation(parcelId, "INBOUND", bag.getId(), bag.getCurrentStandId(),
                        standNo(bag.getCurrentStandId()), null, bag.getBagKind().name(), bag.getStatus().name());
            }
        }
        throw new ParcelNotFoundException(parcelId.toString());
    }

    private String standNo(UUID standId) {
        return standId == null ? null
                : standRepository.findById(standId).map(Stand::getStandNo).orElse(null);
    }
}
