package com.oneday.grid.service;

import com.oneday.grid.dto.response.ProposalResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface GridReplanService {
    // Runs demand scoring → adjacency graph → CP-SAT (BFS fallback) for one city.
    // Used by both NightlyReplanJob (with daIds from DaRosterPort) and the REST API
    // (with caller-supplied daIds).
    ProposalResponse replan(UUID cityId, LocalDate validForDate, List<UUID> daIds);
}
