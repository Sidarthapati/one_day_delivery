package com.oneday.hub.service;

import java.util.UUID;

/**
 * Operator console: "where does this box go?" — resolves a scanned parcel to its current stand by
 * reading the live bag it sits in (the open bag is the directory, M7-D-001). A read, no side effects.
 */
public interface ParcelLocatorService {

    /** Locate a parcel's current bag + stand, or throw {@code ParcelNotFoundException} if unbagged. */
    ParcelLocation locate(UUID parcelId);

    record ParcelLocation(
            UUID parcelId,
            String direction,   // OUTBOUND (flight bag) | INBOUND (delivery bag) | SHELF (hub-collect)
            UUID bagId,
            UUID standId,
            String standNo,
            String flightNo,    // OUTBOUND only
            String bagKind,     // INBOUND only
            String status) {
    }
}
