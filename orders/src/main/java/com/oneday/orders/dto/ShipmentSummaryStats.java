package com.oneday.orders.dto;

import java.util.Map;

/**
 * Aggregate parcel counts for the ops monitoring dashboard. Field names serialise to
 * snake_case via the global Jackson config ({@code byState} → {@code by_state}); map keys
 * pass through verbatim ({@code OpsBucket} / {@code ShipmentState} names).
 *
 * @param total   total shipments in scope
 * @param buckets count per ops bucket ({@code OpsBucket} name → count); all five keys always present
 * @param byState count per raw state ({@code ShipmentState} name → count); only non-zero states present
 */
public record ShipmentSummaryStats(
        long total,
        Map<String, Long> buckets,
        Map<String, Long> byState) {
}
