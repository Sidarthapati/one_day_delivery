package com.oneday.dispatch.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * One DA on a shift's muster: the rostered DA joined with their attendance state for the day.
 * {@code status} is {@code PRESENT} / {@code ABSENT} when a record exists, else {@code PENDING}
 * (rostered but no proximity confirmed yet). {@code method} / {@code distanceM} / {@code detectedAt}
 * are null until a record exists.
 */
public record AttendanceMusterEntry(
        UUID daId,
        String daName,
        String shiftType,
        String status,
        String method,
        Double distanceM,
        Instant detectedAt) {
}
