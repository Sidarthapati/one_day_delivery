package com.oneday.hub.service;

import com.oneday.common.domain.enums.DropType;
import com.oneday.hub.domain.BagKind;
import com.oneday.hub.service.port.ShipmentInfoPort;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The symmetric sort (M7-D-001), both directions with <b>dynamic stand assignment</b>. OUTBOUND
 * (§7.1): resolve the assigned flight, open that flight's bag — allocated a free stand on first
 * open. INBOUND (§8.1, M7-D-012): resolve the {@code hex → DA territory → delivery route} ladder,
 * open a ROUTE bag (van) — or a DA-TERRITORY bag when no van runs — on a free delivery stand. There
 * is no pre-mapped directory either way; the open bag IS the live directory.
 */
public interface SortService {

    /** Resolve flight, bag and stand for a first-mile parcel ready at the origin hub at {@code readyAt} (§7.1). */
    SortResult resolveOutbound(UUID hubId, ShipmentInfoPort.ParcelInfo parcel, Instant readyAt);

    /**
     * Resolve the delivery route/territory bag and stand for a DA_DELIVERY parcel at the dest hub
     * (§8.1, M7-D-012), stage it, and emit the M6 feed (PARCEL_SORTED_FOR_DELIVERY) + DEST_SORT_COMPLETE.
     */
    InboundSortResult resolveInbound(UUID hubId, ShipmentInfoPort.ParcelInfo parcel, Instant sortedAt);

    record InboundSortResult(
            UUID shipmentId,
            String shipmentRef,
            UUID destHexId,
            BagKind bagKind,
            UUID deliveryBagId,
            UUID standId,
            String standNo,
            UUID daTerritoryId,
            UUID routePlanId,
            UUID vanId,
            DropType dropType) {
    }

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
