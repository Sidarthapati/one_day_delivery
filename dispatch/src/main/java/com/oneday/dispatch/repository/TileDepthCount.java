package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.TaskStatus;

import java.util.UUID;

/** Aggregated active-task count for a tile in one status (TileQueueDepthPublisher input). */
public record TileDepthCount(UUID cityId, UUID tileId, TaskStatus status, long count) {
}
