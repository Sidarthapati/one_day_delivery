package com.oneday.dispatch.service;

import com.oneday.dispatch.dto.response.TileQueueResponse;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model for the station manager's tile dispatch view. The current operating date is served from
 * the in-memory authority (live DA status + queues); past dates are read from the database.
 */
public interface StationDispatchService {

    /**
     * Build the dispatch view for {@code tileId} on {@code date}. If {@code scopeCityId} is non-null
     * (a city-scoped station manager) and the tile belongs to another city, throws 403; pass null for
     * an unscoped ADMIN.
     */
    TileQueueResponse tileQueue(UUID tileId, LocalDate date, UUID scopeCityId);
}
