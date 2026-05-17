package com.oneday.grid.dto.request;

import java.util.List;
import java.util.UUID;

// Scenario A only: pre-approval edit inside an existing PROPOSED proposal.
// Does NOT create a new proposal — modifies the existing one in place.
public record RegionEditRequest(
        List<UUID> newTileIds,
        UUID reviewerId
) {}
