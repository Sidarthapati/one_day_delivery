package com.oneday.hub.dto;

import com.oneday.hub.service.BagReassignmentService;

import java.time.LocalDate;
import java.util.UUID;

/** Result of executing a flight reassignment: the target bag now carrying the parcels (§9). */
public record ReassignResponse(
        UUID targetBagId,
        String toFlightNo,
        LocalDate toFlightDate,
        String destHub,
        int movedCount,
        String standNo,
        UUID manifestId) {

    public static ReassignResponse from(BagReassignmentService.ReassignResult r) {
        var b = r.targetBag();
        return new ReassignResponse(b.getId(), b.getFlightNo(), b.getFlightDate(), b.getDestHub(),
                r.movedCount(), r.standNo(), r.manifestId());
    }
}
