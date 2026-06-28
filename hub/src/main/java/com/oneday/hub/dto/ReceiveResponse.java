package com.oneday.hub.dto;

import com.oneday.hub.domain.DiscrepancyType;
import com.oneday.hub.service.HubReceivingService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What the operator console shows after a scan-in: the receipt + the resolved sort. {@code direction}
 * says which path the parcel took — OUTBOUND fills the flight fields, INBOUND the route/territory
 * fields, and a HUB_COLLECT shelf placement leaves both blank.
 */
public record ReceiveResponse(
        UUID receiptId,
        UUID shipmentId,
        String shipmentRef,
        boolean reconciled,
        DiscrepancyType discrepancyType,
        String direction,
        String sortKey,
        UUID bagId,
        UUID standId,
        String standNo,
        // OUTBOUND (flight) fields
        String flightNo,
        LocalDate flightDate,
        String destHub,
        Instant bagCutoff,
        // INBOUND (delivery) fields
        String bagKind,
        UUID destHexId,
        UUID daTerritoryId,
        UUID routePlanId,
        UUID loopId) {

    public static ReceiveResponse from(HubReceivingService.ReceiveResult r) {
        if (r.sort() != null) {
            var s = r.sort();
            return new ReceiveResponse(r.receiptId(), r.shipmentId(), r.shipmentRef(),
                    r.reconciled(), r.discrepancyType(), "OUTBOUND",
                    s.sortKey(), s.bagId(), s.standId(), s.standNo(),
                    s.flightNo(), s.flightDate(), s.destHub(), s.bagCutoff(),
                    null, null, null, null, null);
        }
        if (r.inboundSort() != null) {
            var s = r.inboundSort();
            return new ReceiveResponse(r.receiptId(), r.shipmentId(), r.shipmentRef(),
                    r.reconciled(), r.discrepancyType(), "INBOUND",
                    s.destHexId() != null ? s.destHexId().toString() : null,
                    s.deliveryBagId(), s.standId(), s.standNo(),
                    null, null, null, null,
                    s.bagKind() != null ? s.bagKind().name() : null,
                    s.destHexId(), s.daTerritoryId(), s.routePlanId(), s.loopId());
        }
        // HUB_COLLECT shelf placement — no bag, no stand.
        return new ReceiveResponse(r.receiptId(), r.shipmentId(), r.shipmentRef(),
                r.reconciled(), r.discrepancyType(), "HUB_COLLECT",
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
