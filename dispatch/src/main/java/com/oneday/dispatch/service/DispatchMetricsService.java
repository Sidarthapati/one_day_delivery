package com.oneday.dispatch.service;

import com.oneday.dispatch.dto.response.DaDetailResponse;
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

    /**
     * One DA's detail for a date: identity + pace + today's tasks (urgency-sorted) + a short history.
     * {@code scopeCityId} null → any city (ADMIN); otherwise the DA must have a task in that city on
     * {@code date} (else 404) and only that city's tasks/history are returned.
     */
    DaDetailResponse daDetail(UUID daId, LocalDate date, UUID scopeCityId);
}
