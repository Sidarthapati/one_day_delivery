package com.oneday.dispatch.service;

import com.oneday.dispatch.dto.response.DispatchExecutionStats;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only control-tower metrics over the dispatch queue: delivery attempt-success and per-DA pace.
 * Purely DB-aggregated (the queue rows carry {@code completed_at}), so it's independent of the live
 * in-memory tile authority that {@link StationDispatchService} uses.
 */
public interface DispatchMetricsService {

    /** @param scopeCityId null → all cities (ADMIN); otherwise restrict to that city. */
    DispatchExecutionStats execution(LocalDate date, UUID scopeCityId);
}
