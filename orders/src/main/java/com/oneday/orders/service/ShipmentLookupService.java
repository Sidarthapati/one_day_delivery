package com.oneday.orders.service;

import com.oneday.orders.dto.ShipmentInfo;

import java.util.Optional;

/**
 * Public, read-only lookup of a shipment's operational facts (confirmed weight, drop type, dest,
 * SLA) by its reference / barcode string. The cross-module seam M7 (hub) wires for confirmed bag
 * weight and the destination drop branch — callers import this interface and {@link ShipmentInfo},
 * never the {@code Shipment} entity (CLAUDE.md cross-module rule).
 */
public interface ShipmentLookupService {

    /** @param shipmentRef the shipment reference / barcode string (e.g. {@code BLR-20260627-000042}). */
    Optional<ShipmentInfo> findByRef(String shipmentRef);
}
