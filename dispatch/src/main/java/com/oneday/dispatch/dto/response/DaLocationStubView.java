package com.oneday.dispatch.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One DA "location visit" for the station DA-detail dwell section: where the DA was, how long they spent
 * there ({@code dwellSeconds} = first arrival → last completion), and the orders/shipments worked in that
 * visit. Two rows share a location when the DA visited it twice — each is its own ticket. Serialized
 * snake_case (global Jackson).
 */
public record DaLocationStubView(
        UUID stubId,
        String status,              // OPEN | CLOSED
        String locationKey,
        double lat,
        double lon,
        String addressText,         // display address, from the first task's contact (nullable)
        UUID tileId,
        Instant openedAt,
        Instant firstArrivedAt,
        Instant lastCompletedAt,
        Instant closedAt,
        int taskCount,
        int processedCount,
        int failedCount,
        Long dwellSeconds,
        List<Item> items) {

    /** One order/shipment worked in the visit. */
    public record Item(String shipmentRef, String orderRef, String taskType, String status) {}
}
