package com.oneday.dispatch.service;

import com.oneday.dispatch.dto.request.DispositionRequest;
import com.oneday.dispatch.dto.response.DispositionResponse;
import com.oneday.dispatch.dto.response.DispositionSlotsResponse;
import com.oneday.dispatch.dto.response.ManagerDispositionEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DA self-service dispositions (breaks / auxiliary company-work / day-off). A personal BREAK
 * auto-approves within the DA's deadline-aware slots and daily allowance and holds his territory
 * (status {@code ON_BREAK}, nothing written to the grid); AUXILIARY / DAY_OFF are manager-approved.
 * An overstay past the scheduled end + grace is marked OVERSTAYED and surfaced to the station manager —
 * the actual territory vacate stays the manager's existing mark-absent action (never automatic).
 */
public interface DaDispositionService {

    /** The DA's allowed break windows for today, remaining allowance, and any active disposition. */
    DispositionSlotsResponse slots(UUID daId);

    /** Raise a disposition. BREAK auto-approves (→ ON_BREAK); AUXILIARY / DAY_OFF are left PENDING. */
    DispositionResponse request(UUID daId, DispositionRequest request, UUID actorUserId);

    /** The DA taps "I'm back": closes their active break/auxiliary and restores them to work. */
    DispositionResponse end(UUID daId, UUID actorUserId);

    /** Station manager approves a PENDING AUXILIARY request (→ ACTIVE, ON_BREAK). */
    DispositionResponse approve(UUID dispositionId, UUID managerId, UUID scopeCityId);

    /** Station manager rejects a PENDING AUXILIARY / DAY_OFF request. */
    DispositionResponse reject(UUID dispositionId, UUID managerId, UUID scopeCityId);

    /** The station manager's view for a city: pending requests + active/overstayed dispositions. */
    List<ManagerDispositionEntry> managerView(UUID cityId);

    /** Monitor sweep: bump overstay escalation, mark OVERSTAYED past grace, close breaks the DA left. */
    void sweep(Instant now);
}
