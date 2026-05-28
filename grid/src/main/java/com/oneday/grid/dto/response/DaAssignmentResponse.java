package com.oneday.grid.dto.response;

import com.oneday.grid.domain.AssignmentStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DaAssignmentResponse(
        UUID daId,
        LocalDate validDate,
        UUID proposalId,
        List<UUID> hexIds,
        AssignmentStatus status
) {}
