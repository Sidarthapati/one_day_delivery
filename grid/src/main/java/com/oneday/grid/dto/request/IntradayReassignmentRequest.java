package com.oneday.grid.dto.request;

import java.util.List;
import java.util.UUID;

// Scenario B only: tile move on an already-ACTIVE plan.
// Creates a brand-new INTRADAY_OVERRIDE proposal that requires its own approval.
public record IntradayReassignmentRequest(
        UUID cityId,
        UUID fromDaId,
        UUID toDaId,
        List<UUID> tileIdsToMove,
        UUID requestedBy
) {}
