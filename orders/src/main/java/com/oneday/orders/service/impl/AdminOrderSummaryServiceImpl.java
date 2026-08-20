package com.oneday.orders.service.impl;

import com.oneday.orders.dto.ShipmentSummaryStats;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.StateCount;
import com.oneday.orders.service.AdminOrderSummaryService;
import com.oneday.orders.service.OpsBucket;
import org.springframework.stereotype.Service;

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

    private final ShipmentRepository shipmentRepository;

    // ponytail: 30s per-instance memo, keyed by city scope. Protects the (remote) DB from
    // GROUP-BY on every ops poll; on one app node it's enough. Reach for a shared cache only if
    // we scale out and cross-node staleness actually diverges.
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long at, ShipmentSummaryStats value) {}

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
}
