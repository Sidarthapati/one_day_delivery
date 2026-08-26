package com.oneday.dispatch.service;

import com.oneday.common.domain.Shift;
import com.oneday.dispatch.dto.response.AbsencePreviewResponse;
import com.oneday.dispatch.dto.response.AttendanceMusterEntry;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Geocoded DA attendance (M5). A GPS fix within the hub geofence — or the DA tapping "I've arrived" —
 * marks the DA present; a station manager can override after the shift cutoff. Marking a DA absent
 * hands off to {@link AbsenceReassignmentService} so their territory is re-covered.
 */
public interface AttendanceService {

    /**
     * Reactive auto-present: called on every DA GPS ping. If the fix is within the hub geofence and no
     * attendance row exists yet for the day, records the DA present (AUTO_GEOFENCE). Cheap and
     * idempotent — an in-memory per-day guard keeps it off the DB once the DA is marked.
     */
    void onGpsFix(UUID daId, UUID cityId, String shiftType, double lat, double lon, Instant pingAt);

    /** DA self "I've arrived": marks present if within the hub geofence (else 422). Coords optional —
     *  falls back to the DA's latest GPS fix. Idempotent — if the DA is already settled for the day
     *  (auto-present, manual, or a manager override) it returns that record without re-checking the
     *  geofence, so tapping again after leaving the hub never 422s. */
    AttendanceMusterEntry checkIn(UUID daId, Double lat, Double lon);

    /** The DA's own attendance for today: {@code PRESENT} / {@code ABSENT} with how + distance, or
     *  {@code PENDING} when nothing is recorded yet. Drives the driver-app card so it reflects a GPS
     *  auto-present, not just a tap in the current app session. */
    AttendanceMusterEntry today(UUID daId);

    /** The day's attendance for a city + shift: the rostered DAs joined with their present/absent state. */
    List<AttendanceMusterEntry> muster(String cityCode, LocalDate date, Shift shift);

    /** Station manager confirms a DA present (discards the attendance alert). */
    void markPresent(UUID daId, LocalDate date, UUID actorUserId, UUID scopeCityId);

    /** Station manager marks a DA absent → records ABSENT and triggers the reassignment preview. */
    AbsencePreviewResponse markAbsent(UUID daId, LocalDate date, String reason, UUID actorUserId, UUID scopeCityId);
}
