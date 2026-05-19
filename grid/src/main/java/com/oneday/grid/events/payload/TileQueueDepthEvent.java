package com.oneday.grid.events.payload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by M4 (Orders) on topic orders.tile_queue_depth whenever the unserved
 * order count for a tile changes during a shift. M3 uses this to update the
 * in-memory load score and trigger overload alerts.
 */
public record TileQueueDepthEvent(
        UUID tileId,
        UUID cityId,
        LocalDate date,
        int unservedOrders,
        int bookedOrders,
        Instant recordedAt
) {}
