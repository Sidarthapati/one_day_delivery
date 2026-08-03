package com.oneday.dispatch.service;

import com.oneday.dispatch.dto.response.TileQueueResponse;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read/act model for the station manager's tile dispatch view. The current operating date is served
 * from the in-memory authority (live DA status + queues); past dates are read from the database.
 */
public interface StationDispatchService {

    /**
     * Build the dispatch view for {@code tileId} on {@code date}. If {@code scopeCityId} is non-null
     * (a city-scoped station manager) and the tile belongs to another city, throws 403; pass null for
     * an unscoped ADMIN.
     */
    TileQueueResponse tileQueue(UUID tileId, LocalDate date, UUID scopeCityId);

    /**
     * Manually assign a PENDING deferred task on {@code tileId} to {@code daId} — a station-manager
     * override of {@link DispatchService}'s automatic pick, still cron-feasibility gated. Validates
     * {@code deferredId} actually belongs to {@code tileId} (404 otherwise) and, for a city-scoped
     * caller, that it's in their city (403 otherwise) before delegating to {@link DispatchService}.
     */
    AssignmentResult assignDeferred(UUID tileId, UUID deferredId, UUID daId, UUID scopeCityId);

    /**
     * Manually escalate a PENDING deferred task on {@code tileId} to M11. Same tile/city validation
     * as {@link #assignDeferred}.
     */
    void escalateDeferred(UUID tileId, UUID deferredId, UUID scopeCityId);
}
