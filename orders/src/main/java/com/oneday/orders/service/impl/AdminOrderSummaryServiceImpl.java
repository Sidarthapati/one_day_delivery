package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.dto.ShipmentAgeingStats;
import com.oneday.orders.dto.ShipmentSummaryStats;
import com.oneday.orders.repository.AgeingBandCount;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.StateCount;
import com.oneday.orders.service.AdminOrderSummaryService;
import com.oneday.orders.service.OpsBucket;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @see AdminOrderSummaryService
 */
@Service
class AdminOrderSummaryServiceImpl implements AdminOrderSummaryService {

    private static final long TTL_MILLIS = 30_000L;
    private static final String ALL_CITIES_KEY = "__all__";

    // ponytail: hour bands for a one-day product (SLA is intraday). Constants for now; make them
    // config-driven only if ops want to tune the thresholds.
    private static final List<String> BANDS = List.of("<2h", "2-4h", "4-8h", ">8h");
    private static final long BAND_T1_SECONDS = 2 * 3600L;
    private static final long BAND_T2_SECONDS = 4 * 3600L;
    private static final long BAND_T3_SECONDS = 8 * 3600L;

    private final ShipmentRepository shipmentRepository;

    // ponytail: 30s per-instance memo, keyed by city scope. Protects the (remote) DB from
    // GROUP-BY on every ops poll; on one app node it's enough. Reach for a shared cache only if
    // we scale out and cross-node staleness actually diverges.
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, CachedAgeing> ageingCache = new ConcurrentHashMap<>();

    private record Cached(long at, ShipmentSummaryStats value) {}
    private record CachedAgeing(long at, ShipmentAgeingStats value) {}

    AdminOrderSummaryServiceImpl(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    @Override
    public ShipmentSummaryStats summary(String cityScope) {
        String key = (cityScope == null) ? ALL_CITIES_KEY : cityScope;
        Cached hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at() < TTL_MILLIS) {
            return hit.value();
        }
        ShipmentSummaryStats fresh = compute(cityScope);
        cache.put(key, new Cached(now, fresh));
        return fresh;
    }

    private ShipmentSummaryStats compute(String cityScope) {
        List<StateCount> counts = (cityScope == null)
                ? shipmentRepository.countByState()
                : shipmentRepository.countByStateForCity(cityScope);

        Map<OpsBucket, Long> buckets = new EnumMap<>(OpsBucket.class);
        for (OpsBucket b : OpsBucket.values()) {
            buckets.put(b, 0L);
        }
        Map<String, Long> byState = new LinkedHashMap<>();
        long total = 0L;
        for (StateCount c : counts) {
            long n = c.getCount();
            total += n;
            byState.put(c.getState().name(), n);
            buckets.merge(OpsBucket.of(c.getState()), n, Long::sum);
        }

        Map<String, Long> bucketsByName = new LinkedHashMap<>();
        buckets.forEach((b, n) -> bucketsByName.put(b.name(), n));
        return new ShipmentSummaryStats(total, bucketsByName, byState);
    }

    @Override
    public ShipmentAgeingStats ageing(String cityScope) {
        String key = (cityScope == null) ? ALL_CITIES_KEY : cityScope;
        CachedAgeing hit = ageingCache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at() < TTL_MILLIS) {
            return hit.value();
        }
        ShipmentAgeingStats fresh = computeAgeing(cityScope);
        ageingCache.put(key, new CachedAgeing(now, fresh));
        return fresh;
    }

    private ShipmentAgeingStats computeAgeing(String cityScope) {
        List<AgeingBandCount> rows = shipmentRepository.ageingByStateAndBand(
                cityScope, BAND_T1_SECONDS, BAND_T2_SECONDS, BAND_T3_SECONDS);

        int n = BANDS.size();
        Map<OpsBucket, long[]> byBucket = new EnumMap<>(OpsBucket.class);
        long[] bandTotals = new long[n];
        long total = 0L;
        for (AgeingBandCount r : rows) {
            OpsBucket bucket = OpsBucket.of(ShipmentState.valueOf(r.getState()));
            byBucket.computeIfAbsent(bucket, b -> new long[n])[r.getBand()] += r.getCnt();
            bandTotals[r.getBand()] += r.getCnt();
            total += r.getCnt();
        }

        Map<String, List<Long>> byBucketOut = new LinkedHashMap<>();
        byBucket.forEach((b, counts) -> byBucketOut.put(b.name(), boxed(counts)));
        return new ShipmentAgeingStats(total, BANDS, byBucketOut, boxed(bandTotals));
    }

    private static List<Long> boxed(long[] a) {
        List<Long> out = new ArrayList<>(a.length);
        for (long v : a) {
            out.add(v);
        }
        return out;
    }
}
