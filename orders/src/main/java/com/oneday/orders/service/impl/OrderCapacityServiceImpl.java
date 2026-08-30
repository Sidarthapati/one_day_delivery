package com.oneday.orders.service.impl;

import com.oneday.common.log.AuditLog;
import com.oneday.common.port.DaPickupLoadPort;
import com.oneday.orders.config.OrderCapacityProperties;
import com.oneday.orders.domain.ParcelOrder;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.OrderCapacityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * @see OrderCapacityService
 */
@Service
class OrderCapacityServiceImpl implements OrderCapacityService {

    private static final Logger log = LoggerFactory.getLogger(OrderCapacityServiceImpl.class);

    private final ShipmentRepository shipmentRepository;
    private final OrderCapacityProperties properties;
    // ObjectProvider so the orders module (and its test slice) still wires without the dispatch adapter;
    // absent port → no capacity signal → skip the gate (same graceful pattern as the tracking courier port).
    private final ObjectProvider<DaPickupLoadPort> daPickupLoadPort;

    OrderCapacityServiceImpl(ShipmentRepository shipmentRepository,
                             OrderCapacityProperties properties,
                             ObjectProvider<DaPickupLoadPort> daPickupLoadPort) {
        this.shipmentRepository = shipmentRepository;
        this.properties = properties;
        this.daPickupLoadPort = daPickupLoadPort;
    }

    @Override
    @Transactional(readOnly = true)
    public void ensureCapacityForAdd(ParcelOrder order, int newParcelChargeableGrams) {
        DaPickupLoadPort port = daPickupLoadPort.getIfAvailable();
        if (port == null) {
            return;   // dispatch not wired (e.g. isolated test) — nothing to weigh against
        }

        // Find the DA (if any) already on this order's pickup. All of a B2B order's parcels share the
        // merchant's pickup location, so the first sibling with an assignment identifies that DA.
        List<Shipment> siblings = shipmentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        Optional<DaPickupLoadPort.AssignedPickupLoad> assigned = siblings.stream()
                .map(s -> port.assignedPickupLoad(s.getId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
        if (assigned.isEmpty()) {
            return;   // no DA on the pickup yet — dispatch will place the new parcel normally
        }

        // Sum the chargeable weight already committed to that DA's vehicle (its whole active pickup
        // queue, across every order), then check the new parcel still fits.
        List<UUID> onVehicle = assigned.get().pickupShipmentIds();
        int currentGrams = 0;
        for (Shipment s : shipmentRepository.findAllById(onVehicle)) {
            if (s.getChargeableWeightGrams() != null) {
                currentGrams += s.getChargeableWeightGrams();
            }
        }

        int capacityGrams = properties.capacityGramsFor(order.getCityId());
        int projected = currentGrams + newParcelChargeableGrams;
        if (projected > capacityGrams) {
            AuditLog.event("order.repair.capacity_rejected")
                    .kv("orderRef", order.getOrderRef())
                    .kv("daId", assigned.get().daId())
                    .kv("currentGrams", currentGrams)
                    .kv("newParcelGrams", newParcelChargeableGrams)
                    .kv("capacityGrams", capacityGrams)
                    .log();
            throw new DaCapacityExceededException(String.format(
                    "The delivery associate's vehicle is at capacity for today's pickup "
                            + "(%.1f kg of %.1f kg used); this %.1f kg parcel can't be added.",
                    currentGrams / 1000.0, capacityGrams / 1000.0, newParcelChargeableGrams / 1000.0));
        }

        log.debug("Capacity OK for order {} DA {}: {}g + {}g <= {}g",
                order.getOrderRef(), assigned.get().daId(), currentGrams, newParcelChargeableGrams, capacityGrams);
    }
}
