package com.oneday.grid.dto.response;

import com.oneday.grid.domain.ProposalStatus;

import java.time.Instant;
import java.util.UUID;

public record TileShareResponse(
        UUID proposalId,
        UUID daId,
        UUID tileId,
        ProposalStatus status,
        Instant proposedAt
) {}
