package com.oneday.grid.events.payload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by M3 on topic grid.tile_overload_alert when a tile's adjusted load score
 * has been above the warning/critical threshold for the required sustained minutes.
 * Consumed by M5 (Dispatch) for intraday rebalancing and M10 (SLA) for monitoring.
 */
public record TileOverloadAlertEvent(
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
