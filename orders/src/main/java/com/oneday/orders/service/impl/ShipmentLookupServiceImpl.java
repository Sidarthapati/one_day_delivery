package com.oneday.orders.service.impl;

import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.ShipmentInfo;
import com.oneday.orders.repository.ParcelOrderRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ShipmentLookupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Reads {@code findByShipmentRef} and projects to the public {@link ShipmentInfo}. */
@Service
class ShipmentLookupServiceImpl implements ShipmentLookupService {

    private final ShipmentRepository shipmentRepository;
    private final ParcelOrderRepository parcelOrderRepository;

    ShipmentLookupServiceImpl(ShipmentRepository shipmentRepository,
                              ParcelOrderRepository parcelOrderRepository) {
        this.shipmentRepository = shipmentRepository;
        this.parcelOrderRepository = parcelOrderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShipmentInfo> findByRef(String shipmentRef) {
        return shipmentRepository.findByShipmentRef(shipmentRef).map(this::toInfo);
    }

    private ShipmentInfo toInfo(Shipment s) {
        // Resolve the order back-ref lazily — only shipments that carry an order_id hit parcel_orders.
        String orderRef = s.getOrderId() == null
                ? null
                : parcelOrderRepository.findOrderRefById(s.getOrderId()).orElse(null);
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
                null, // slaDeadline wired when M10's commitment timestamp lands
                s.getOrderId(),
                orderRef);
    }
}
