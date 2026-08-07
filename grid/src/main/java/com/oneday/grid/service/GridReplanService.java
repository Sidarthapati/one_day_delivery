package com.oneday.grid.service;

import com.oneday.common.domain.Shift;
import com.oneday.grid.dto.response.ProposalResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface GridReplanService {
    // Runs demand scoring → adjacency graph → CP-SAT (BFS fallback) for one city + shift.
    // Used by both NightlyReplanJob (with daIds from DaRosterPort, once per shift) and the REST API
    // (with caller-supplied daIds). The resulting proposal is stamped with the shift.
    ProposalResponse replan(UUID cityId, LocalDate validForDate, Shift shift, List<UUID> daIds);
}
