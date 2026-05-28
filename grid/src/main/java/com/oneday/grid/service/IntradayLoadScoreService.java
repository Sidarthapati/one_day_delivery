package com.oneday.grid.service;

import com.oneday.grid.dto.response.TileLoadScoreResponse;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public interface IntradayLoadScoreService {
    TileLoadScoreResponse getLoadScore(UUID tileId, LocalDate date);
    // Called by TileQueueDepthConsumer on each Kafka event (last-write wins).
    void updateQueueDepth(UUID cityId, LocalDate date, Map<UUID, Integer> unservedByHex);
    // Called by IntradayMonitorJob at shift start (07:00) to zero out the previous shift's data.
    void resetForShift();
}
