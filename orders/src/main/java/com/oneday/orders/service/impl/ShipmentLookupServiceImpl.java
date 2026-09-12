package com.oneday.orders.service.impl;

import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.BarcodeResolution;
import com.oneday.orders.dto.ShipmentInfo;
import com.oneday.orders.repository.ParcelOrderRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ShipmentLookupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    @Override
    @Transactional(readOnly = true)
    public Optional<BarcodeResolution> findActiveByParcelId(String code) {
        // A scanned label can carry either the M8 barcode (parcel_id) or the shipment ref — the return
        // reuses the ORIGINAL parcel's physical label, so we resolve both ways and prefer the live return
        // child. Try the ref first (an exact shipment), then fall back to the shared barcode string.
        Shipment byRef = shipmentRepository.findByShipmentRef(code).orElse(null);
        if (byRef != null) {
            return Optional.of(resolveFrom(code, byRef));
        }
        List<Shipment> matches = shipmentRepository.findByParcelId(code);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        // At most two rows share a barcode (R1): an original and its single return child <ref>_R.
        // Prefer the return child — once RTO is on, the physical label belongs to the active return leg.
        Shipment child = matches.stream()
                .filter(s -> s.getReturnOfShipmentId() != null).findFirst().orElse(null);
        Shipment original = matches.stream()
                .filter(s -> s.getReturnOfShipmentId() == null).findFirst().orElse(null);
        if (child != null) {
            String originalRef = original != null ? original.getShipmentRef() : null;
            return Optional.of(new BarcodeResolution(code, child.getId(), child.getShipmentRef(),
                    child.getState(), true, originalRef));
        }
        Shipment active = original != null ? original : matches.get(0);
        return Optional.of(new BarcodeResolution(code, active.getId(), active.getShipmentRef(),
                active.getState(), false, null));
    }

    /** Resolve from a shipment matched by its ref: an original with a live return child routes to the
     *  child (the reused-label return leg); otherwise the shipment itself (a child already is a return). */
    private BarcodeResolution resolveFrom(String code, Shipment s) {
        if (s.getReturnOfShipmentId() == null && s.getReturnShipmentId() != null) {
            Shipment child = shipmentRepository.findById(s.getReturnShipmentId()).orElse(null);
            if (child != null) {
                return new BarcodeResolution(code, child.getId(), child.getShipmentRef(),
                        child.getState(), true, s.getShipmentRef());
            }
        }
        boolean isReturn = s.getReturnOfShipmentId() != null;
        String originalRef = isReturn
                ? shipmentRepository.findById(s.getReturnOfShipmentId())
                        .map(Shipment::getShipmentRef).orElse(null)
                : null;
        return new BarcodeResolution(code, s.getId(), s.getShipmentRef(), s.getState(), isReturn, originalRef);
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
                orderRef,
                // R4 pending-RTO flag: intent recorded, not yet resolved, no return child minted.
                s.getRtoRequestedAt() != null && s.getRtoResolvedAt() == null
                        && s.getReturnShipmentId() == null);
    }
}
