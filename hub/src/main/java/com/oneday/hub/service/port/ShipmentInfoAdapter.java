package com.oneday.hub.service.port;

import com.oneday.orders.dto.ShipmentInfo;
import com.oneday.orders.service.ShipmentLookupService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Real M4 wiring: delegates to the public {@link ShipmentLookupService} and maps to {@link ParcelInfo}. */
@Component
class ShipmentInfoAdapter implements ShipmentInfoPort {

    private final ShipmentLookupService shipmentLookupService;

    ShipmentInfoAdapter(ShipmentLookupService shipmentLookupService) {
        this.shipmentLookupService = shipmentLookupService;
    }

    @Override
    public Optional<ParcelInfo> lookup(String shipmentRef) {
        return shipmentLookupService.findByRef(shipmentRef).map(ShipmentInfoAdapter::toParcelInfo);
    }

    private static ParcelInfo toParcelInfo(ShipmentInfo s) {
        return new ParcelInfo(
                s.shipmentId(),
                s.shipmentRef(),
                s.state(),
                s.chargeableWeightGrams(),
                s.dropType(),
                s.deliveryType(),
                s.originCity(),
                s.destCity(),
                s.destPincode(),
                s.slaDeadline());
    }
}
