package com.oneday.orders.service.impl;

import com.oneday.common.port.ShipmentLocationPort;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Orders-side implementation of {@link ShipmentLocationPort}: reads the receiver's geocoded address
 * off the shipment so M5 can assign a hub-return delivery against the real drop lat/lon.
 */
@Component
class ShipmentLocationAdapter implements ShipmentLocationPort {

    private final ShipmentRepository shipmentRepository;

    ShipmentLocationAdapter(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DropLocation> dropLocation(UUID shipmentId) {
        return shipmentRepository.findById(shipmentId)
                .filter(s -> hasCoords(s.getDestAddress()))
                .map(s -> new DropLocation(
                        s.getDestAddress().getLatitude(),
                        s.getDestAddress().getLongitude(),
                        s.getDestTileId()));
    }

    private static boolean hasCoords(Address a) {
        return a != null && a.getLatitude() != null && a.getLongitude() != null;
    }
}
