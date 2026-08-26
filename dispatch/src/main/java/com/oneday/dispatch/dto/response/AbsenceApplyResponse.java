package com.oneday.dispatch.dto.response;

import com.oneday.dispatch.domain.AbsenceStatus;

import java.util.UUID;

/** Outcome of applying a midday-absence plan (manager click or auto-approve timeout). */
public record AbsenceApplyResponse(
        UUID eventId,
        AbsenceStatus status,
        int reassignedHexCount,
        int movedTaskCount,
        int custodyTaskCount,
        int orphanCount) {
}
