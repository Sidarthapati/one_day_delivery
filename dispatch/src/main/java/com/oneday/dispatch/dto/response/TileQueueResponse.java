package com.oneday.dispatch.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Station-manager tile dispatch view (design §15.9): the DAs serving a tile on a date, each with
 * their live status, queue depth, cron slack, and ordered queue, plus the tile's deferred count.
 */
public record TileQueueResponse(
        UUID tileId,
        LocalDate operatingDate,
        List<DaQueueView> das,
        int deferredCount) {

    public record DaQueueView(
            UUID daId,
            String status,
            int queueDepth,
            Long cronSlackMinutes,
            List<TaskView> queue) {
    }

    public record TaskView(
            UUID taskId,
            UUID shipmentId,
            int queuePosition,
            String status,
            Instant expectedEta,
            boolean crossTerritory,
            String taskType) {
    }
}
