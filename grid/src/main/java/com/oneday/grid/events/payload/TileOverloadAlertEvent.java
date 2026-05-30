package com.oneday.grid.events.payload;

import com.oneday.common.kafka.enums.GridEventType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by M3 on the consolidated topic oneday.grid.events (eventType = TILE_OVERLOAD_ALERT)
 * when a tile's adjusted load score has been above the warning/critical threshold for the
 * required sustained minutes.
 * Consumed by M5 (Dispatch) for intraday rebalancing and M10 (SLA) for monitoring.
 */
public record TileOverloadAlertEvent(
        GridEventType eventType,
        UUID cityId,
        UUID tileId,
        UUID daId,
        LocalDate date,
        String severity,
        double expectedOrders,
        int unservedOrders,
        double adjustedLoadScore,
        int sustainedMinutes,
        Instant alertedAt
) {}
