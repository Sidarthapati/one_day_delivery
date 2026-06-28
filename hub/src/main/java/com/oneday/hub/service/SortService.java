package com.oneday.hub.service;

import com.oneday.hub.service.port.ShipmentInfoPort;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The symmetric sort (M7-D-001). PR #1 implements the OUTBOUND direction with <b>dynamic stand
 * assignment</b>: resolve the assigned flight, then open (or find) that flight's bag — which is
 * allocated a free stand from the hub's pool on first open. There is no pre-mapped destination→stand
 * directory; the open {@code flight_bag} IS the live directory. The INBOUND direction lands in PR #2.
 */
public interface SortService {

    /** Resolve flight, bag and stand for a first-mile parcel ready at the origin hub at {@code readyAt} (§7.1). */
    SortResult resolveOutbound(UUID hubId, ShipmentInfoPort.ParcelInfo parcel, Instant readyAt);

    record SortResult(
            UUID shipmentId,
            String shipmentRef,
            String sortKey,
            UUID bagId,
            UUID standId,
            String standNo,
            String flightNo,
            LocalDate flightDate,
            String originHub,
            String destHub,
            Instant bagCutoff,
            Instant flightArrival) {
    }
}
