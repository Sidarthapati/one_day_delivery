package com.oneday.grid.service;

import com.oneday.grid.domain.TileDemandSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DemandScoringService {
    // Computes and persists TileDemandSnapshot for all active tiles in city for given date.
    List<TileDemandSnapshot> computeAndPersistDemand(UUID cityId, LocalDate date);
}
