package com.oneday.routing.dto;

import java.util.List;
import java.util.UUID;

/**
 * Body for {@code POST /routing/plans/{planId}/override} (§10). A station manager / admin edits a
 * live APPROVED plan; M6 supersedes it with a new append-only revision (source MANUAL_OVERRIDE),
 * records a {@code route_override_audit} row, and emits {@code ROUTE_CHANGED}. {@code reassignments}
 * may be empty (e.g. a re-publish with a reason); when present each moves a stop to a new vertex.
 */
public record OverrideRequest(UUID actorId, String reason, List<StopReassignment> reassignments) {

    public List<StopReassignment> reassignmentsOrEmpty() {
        return reassignments != null ? reassignments : List.of();
    }
}
