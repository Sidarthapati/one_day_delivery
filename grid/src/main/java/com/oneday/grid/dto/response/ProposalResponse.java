package com.oneday.grid.dto.response;

import com.oneday.grid.domain.AdjacencySource;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.ProposalType;
import com.oneday.grid.domain.SolverType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ProposalResponse(
        UUID id,
        UUID cityId,
        LocalDate validForDate,
        ProposalStatus status,
        ProposalType proposalType,
        SolverType solverType,
        AdjacencySource adjacencySource,
        Double optimalityGapPct,
        int totalDas,
        Double coveragePct,
        List<UUID> understaffedHexIds,
        Instant proposedAt,
        UUID reviewedBy,
        Instant reviewedAt,
        String notes,
        List<RegionResponse> regions
) {}
