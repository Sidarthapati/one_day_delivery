package com.oneday.grid.service;

import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.HexDemandSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AssignmentService {
    // Computes, persists, and returns the AssignmentProposal (with linked regions + assignments).
    AssignmentProposal computeProposal(UUID cityId,
                                       LocalDate validForDate,
                                       List<HexDemandSnapshot> demand,
                                       Map<UUID, List<UUID>> adjacencyGraph,
                                       List<UUID> availableDaIds);
}
