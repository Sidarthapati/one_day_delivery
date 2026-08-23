package com.oneday.orders.dto;

import java.util.List;
import java.util.Map;

/**
 * Ageing rollup for the ops dashboard — live shipments grouped by {@code OpsBucket} stage and dwell band
 * (time since last scan). {@code bands} names the columns in order (e.g. {@code ["<2h","2-4h","4-8h",">8h"]});
 * every list in {@code byBucket} and {@code bandTotals} is aligned to that order. Serialises snake_case
 * ({@code byBucket} → {@code by_bucket}); map keys pass through verbatim ({@code OpsBucket} names).
 *
 * @param total       live shipments in scope
 * @param bands       ordered band labels (the columns)
 * @param byBucket    OpsBucket name → per-band counts (aligned to {@code bands})
 * @param bandTotals  per-band totals across all buckets (aligned to {@code bands})
 */
public record ShipmentAgeingStats(
        long total,
        List<String> bands,
        Map<String, List<Long>> byBucket,
        List<Long> bandTotals) {
}
