package com.oneday.dispatch.service;

import com.oneday.dispatch.dto.response.AbsenceApplyResponse;
import com.oneday.dispatch.dto.response.AbsencePreviewResponse;

import java.util.List;
import java.util.UUID;

/**
 * Midday DA-absence reassignment (M5). A station manager marks one or more DAs absent; the absent
 * DAs' hexes are split among their territory-neighbors (M3) and every task follows its hex to the new
 * owner. {@link #preview} computes the plan without mutating anything; {@link #apply} commits it
 * (grid override + task moves + custody handoffs). {@link #autoApply} is the timeout path.
 */
public interface AbsenceReassignmentService {

    /** Compute + persist a PENDING plan for the given absent DAs and return it for the manager to review. */
    AbsencePreviewResponse preview(UUID cityId, List<UUID> daIds, String reason, UUID actorUserId);

    /** Apply a PENDING plan on a manager's approval. Idempotent once applied. */
    AbsenceApplyResponse apply(UUID eventId, UUID actorUserId);

    /** Apply a PENDING plan automatically (auto-approve timeout). Idempotent once applied. */
    AbsenceApplyResponse autoApply(UUID eventId);
}
