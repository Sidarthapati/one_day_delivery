package com.oneday.dispatch.dto.response;

import com.oneday.dispatch.domain.DispositionCategory;
import com.oneday.dispatch.domain.DispositionReason;
import com.oneday.dispatch.domain.DispositionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the station manager's disposition view — pending AUXILIARY/DAY_OFF requests awaiting a
 * decision, plus ACTIVE/OVERSTAYED breaks the manager may need to act on. {@code minutesOverdue} is
 * how far past {@code scheduledEnd} an overstay is (0 otherwise).
 */
public record ManagerDispositionEntry(
        UUID id,
        UUID daId,
        String daName,
        DispositionCategory category,
        DispositionReason reason,
        DispositionStatus status,
        String note,
        Instant scheduledEnd,
        int escalationLevel,
        long minutesOverdue) {
}
