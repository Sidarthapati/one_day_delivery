package com.oneday.dispatch.service;

import com.oneday.dispatch.dto.response.AbsenceApplyResponse;
import com.oneday.dispatch.dto.response.AbsencePreviewResponse;
import com.oneday.dispatch.dto.response.DaRosterEntry;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Midday DA-absence reassignment (M5). A station manager marks one or more DAs absent; the absent
 * DAs' hexes are split among their territory-neighbors (M3) and every task follows its hex to the new
 * owner. {@link #preview} computes the plan without mutating anything; {@link #apply} commits it
 * (grid override + task moves + custody handoffs). {@link #autoApply} is the timeout path.
 */
public interface AbsenceReassignmentService {

    /**
     * The DAs on shift for the picker — every DA on the clock (any task type), not just those with
     * delivery scorecards. {@code scopeCityId} null = all cities (ADMIN), else a single city.
     * {@code shift} null = both shifts on the date; else only that shift's DAs (the console filter).
     */
    List<DaRosterEntry> roster(UUID scopeCityId, LocalDate date, com.oneday.common.domain.Shift shift);

    /** Compute + persist a PENDING plan for the given absent DAs and return it for the manager to review. */
    AbsencePreviewResponse preview(UUID cityId, List<UUID> daIds, String reason, UUID actorUserId);

    /**
     * Apply a PENDING plan on a manager's approval. Idempotent once applied. {@code scopeCityId} pins
     * the caller to one city (a STATION_MANAGER's own city); pass {@code null} for an unrestricted
     * (ADMIN) caller. A mismatch is rejected before anything is applied.
     */
    AbsenceApplyResponse apply(UUID eventId, UUID actorUserId, UUID scopeCityId);

    /** Apply a PENDING plan automatically (auto-approve timeout). Idempotent once applied. */
    AbsenceApplyResponse autoApply(UUID eventId);
}
