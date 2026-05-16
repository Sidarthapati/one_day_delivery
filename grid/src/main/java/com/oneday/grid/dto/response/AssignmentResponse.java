package com.oneday.grid.dto.response;

import com.oneday.grid.domain.AssignmentStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AssignmentResponse(
        UUID id,
        UUID proposalId,
        UUID daId,
        UUID tileId,
        LocalDate validDate,
        int nDasOnTile,
        AssignmentStatus status,
        Instant proposedAt,
        UUID approvedBy,
        Instant approvedAt
) {}
