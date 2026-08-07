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
        int deferredCount,
        List<DeferredTaskView> deferredTasks) {

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

    /** A PENDING deferral on this tile — the station board's "unassigned pickups" list. */
    public record DeferredTaskView(
            UUID deferredId,
            UUID shipmentId,
            String taskType,
            String deferReason,
            Instant deferredAt,
            Instant retryAfter,
            int retryCount) {
    }
}
