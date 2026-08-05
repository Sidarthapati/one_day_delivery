package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.port.ShipmentContactPort;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Orders-side implementation of {@link ShipmentContactPort}: batch-reads the sender/receiver contact +
 * a human-readable address for each end so M5's DA task list can offer Call + an address block +
 * "Collect ₹X". Address is composed here (the only side that knows {@link Address}).
 */
@Component
class ShipmentContactAdapter implements ShipmentContactPort {

    private final ShipmentRepository shipmentRepository;

    ShipmentContactAdapter(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ShipmentContact> contactsFor(Collection<UUID> shipmentIds) {
        if (shipmentIds == null || shipmentIds.isEmpty()) {
            return Map.of();
        }
        return shipmentRepository.findAllById(shipmentIds).stream()
                .collect(Collectors.toMap(Shipment::getId, ShipmentContactAdapter::toContact));
    }

    private static ShipmentContact toContact(Shipment s) {
        Long cod = s.getPaymentMode() == PaymentMode.COD ? s.getCodAmountPaise() : null;
        return new ShipmentContact(
                s.getSenderName(), s.getSenderPhone(), compose(s.getOriginAddress()),
                s.getReceiverName(), s.getReceiverPhone(), compose(s.getDestAddress()),
                cod);
    }

    /**
     * One readable line from an {@link Address}: prefer the granular house/street/locality trio, fall
     * back to line1/line2, then landmark, city, pincode. Blanks dropped so the string never shows
     * gaps like ", , ".
     */
    static String compose(Address a) {
        if (a == null) {
            return null;
        }
        boolean hasGranular = notBlank(a.getHouseFloor()) || notBlank(a.getBuildingStreet())
                || notBlank(a.getAreaLocality());
        Stream<String> street = hasGranular
                ? Stream.of(a.getHouseFloor(), a.getBuildingStreet(), a.getAreaLocality())
                : Stream.of(a.getLine1(), a.getLine2());
        String joined = Stream.concat(street, Stream.of(a.getLandmark(), a.getCity(), a.getPincode()))
                .filter(ShipmentContactAdapter::notBlank)
                .map(String::trim)
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
