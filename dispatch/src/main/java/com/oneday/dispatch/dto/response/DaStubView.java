package com.oneday.dispatch.dto.response;

import com.oneday.dispatch.service.DaTaskView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A DA "location ticket" as the server defines it — one {@code da_location_stub} visit and every task in
 * it (pickups and/or drops and/or custody at that doorstep). This is the app's server-authoritative
 * grouping: the driver app renders one card per stub straight from this, instead of re-grouping tasks by
 * rounded coordinates. {@code items} are full {@link DaTaskView}s so a tapped parcel opens its existing
 * detail screen. Serialized snake_case (global Jackson).
 */
public record DaStubView(
        UUID stubId,
        String status,              // OPEN | CLOSED
        String addressText,         // display address, from the first task's contact (nullable)
        double lat,
        double lon,
        Instant openedAt,
        Instant closedAt,
        int taskCount,
        int processedCount,
        int failedCount,
        Long dwellSeconds,
        List<DaTaskView> items) {}
