package com.oneday.hub.dto;

import com.oneday.hub.domain.DiscrepancyType;
import com.oneday.hub.service.HubReceivingService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** What the operator console shows after a scan-in: the receipt + the resolved stand/flight. */
public record ReceiveResponse(
        UUID receiptId,
        UUID shipmentId,
        String shipmentRef,
        boolean reconciled,
        DiscrepancyType discrepancyType,
        String sortKey,
        UUID bagId,
        UUID standId,
        String standNo,
        String flightNo,
        LocalDate flightDate,
        String destHub,
        Instant bagCutoff) {

    public static ReceiveResponse from(HubReceivingService.ReceiveResult r) {
        var s = r.sort();
        return new ReceiveResponse(r.receiptId(), r.shipmentId(), r.shipmentRef(),
                r.reconciled(), r.discrepancyType(),
                s.sortKey(), s.bagId(), s.standId(), s.standNo(),
                s.flightNo(), s.flightDate(), s.destHub(), s.bagCutoff());
    }
}
