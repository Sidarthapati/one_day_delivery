package com.oneday.orders.service.impl;

import com.oneday.common.port.ShipmentRefPort;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orders-side implementation of {@link ShipmentRefPort}: batch-reads shipment ref numbers so M5's
 * DA task list can carry the ref for the ref-keyed delivery-OTP verify call.
 */
@Component
class ShipmentRefAdapter implements ShipmentRefPort {

    private final ShipmentRepository shipmentRepository;

    ShipmentRefAdapter(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> refsFor(Collection<UUID> shipmentIds) {
        if (shipmentIds == null || shipmentIds.isEmpty()) {
            return Map.of();
        }
        return shipmentRepository.findAllById(shipmentIds).stream()
                .filter(s -> s.getShipmentRef() != null)
                .collect(Collectors.toMap(Shipment::getId, Shipment::getShipmentRef));
    }
}
