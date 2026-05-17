package com.oneday.grid.dto.response;

import com.oneday.grid.domain.ProposalStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IntradayReassignmentResponse(
        UUID proposalId,
        UUID cityId,
        UUID fromDaId,
        UUID toDaId,
        List<UUID> tilesMoved,
        ProposalStatus status,
        Instant proposedAt
) {}
