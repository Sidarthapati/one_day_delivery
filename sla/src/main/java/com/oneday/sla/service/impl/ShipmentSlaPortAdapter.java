package com.oneday.sla.service.impl;

import com.oneday.common.port.ShipmentSlaPort;
import com.oneday.sla.repository.SlaShipmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** The single {@link ShipmentSlaPort} implementation — reads the live M10 SLA rollup by shipment id. */
@Component
class ShipmentSlaPortAdapter implements ShipmentSlaPort {

    private final SlaShipmentRepository slaShipmentRepository;

    ShipmentSlaPortAdapter(SlaShipmentRepository slaShipmentRepository) {
        this.slaShipmentRepository = slaShipmentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, SlaStatus> slaFor(Collection<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) {
            return Map.of();
        }
        return slaShipmentRepository.findByShipmentIdIn(shipmentIds).stream()
                .collect(Collectors.toMap(
                        s -> s.getShipmentId(),
                        s -> new SlaStatus(s.getOverallState(), s.isBreached(),
                                s.getUrgencyMinutes(), s.getActByAt())));
    }
}
