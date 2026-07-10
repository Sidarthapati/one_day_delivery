package com.oneday.orders.service.impl;

import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.ShipmentInfo;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ShipmentLookupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Reads {@code findByShipmentRef} and projects to the public {@link ShipmentInfo}. */
@Service
class ShipmentLookupServiceImpl implements ShipmentLookupService {

    private final ShipmentRepository shipmentRepository;

    ShipmentLookupServiceImpl(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShipmentInfo> findByRef(String shipmentRef) {
        return shipmentRepository.findByShipmentRef(shipmentRef).map(ShipmentLookupServiceImpl::toInfo);
    }

    private static ShipmentInfo toInfo(Shipment s) {
        return new ShipmentInfo(
                s.getId(),
                s.getShipmentRef(),
                s.getState(),
                s.getChargeableWeightGrams() != null ? s.getChargeableWeightGrams() : 0,
                s.getDropType(),
                s.getDeliveryType(),
                s.getOriginCity(),
                s.getDestCity(),
                s.getDestPincode(),
                s.getDestTileId(),
                null); // slaDeadline wired when M10's commitment timestamp lands
    }
}
