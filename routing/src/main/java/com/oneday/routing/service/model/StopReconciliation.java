package com.oneday.routing.service.model;

import com.oneday.routing.domain.DiscrepancyType;
import com.oneday.routing.domain.HandoffDirection;

import java.util.List;
import java.util.UUID;

// Result of reconciling one DA's exchange at a stop (§13.1). clean == no discrepancies (a clean
// stop emits HANDOFF_COMPLETED; otherwise one HANDOFF_DISCREPANCY per discrepancy bucket).
public record StopReconciliation(UUID manifestId, int stopSeq, UUID daId, boolean clean,
                                 List<Discrepancy> discrepancies) {

    public record Discrepancy(HandoffDirection direction, DiscrepancyType type, List<UUID> parcelIds) {
    }

    public static StopReconciliation noManifest(int stopSeq, UUID daId) {
        return new StopReconciliation(null, stopSeq, daId, true, List.of());
    }
}
