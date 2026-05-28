package com.oneday.grid.service;

import com.oneday.grid.domain.HexDemandSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DemandScoringService {
    // Computes and persists HexDemandSnapshot for all active hexes in city for given date.
    List<HexDemandSnapshot> computeAndPersistDemand(UUID cityId, LocalDate date);
}
