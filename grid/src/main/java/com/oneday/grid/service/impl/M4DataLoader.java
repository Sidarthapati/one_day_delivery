package com.oneday.grid.service.impl;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Each method runs in its own independent transaction (REQUIRES_NEW).
// When shipment_leg_events doesn't exist yet, the inner transaction rolls back
// without corrupting the outer DemandScoringServiceImpl transaction.
@Service
class M4DataLoader {

    private static final Logger log = LoggerFactory.getLogger(M4DataLoader.class);

    private final EntityManager entityManager;

    M4DataLoader(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public Map<UUID, Double> loadServiceTimeMins(int minPickups) {
        try {
            List<Object[]> rows = entityManager.createNativeQuery("""
                    SELECT tile_id,
                           AVG(EXTRACT(EPOCH FROM (pickup_completed_at - arrived_at_pickup))/60.0)
                    FROM shipment_leg_events
                    WHERE arrived_at_pickup IS NOT NULL
                      AND pickup_completed_at IS NOT NULL
                      AND created_at >= now() - interval '7 days'
                    GROUP BY tile_id
                    HAVING COUNT(*) >= :minPickups
                    """)
                    .setParameter("minPickups", minPickups)
                    .getResultList();
            Map<UUID, Double> result = new HashMap<>();
            for (Object[] row : rows) {
                result.put(UUID.fromString(row[0].toString()), ((Number) row[1]).doubleValue());
            }
            return result;
        } catch (Exception e) {
            log.debug("M4 service_time data unavailable (expected before M4 launch): {}", e.getMessage());
            return Map.of();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public Map<UUID, Double> loadInterStopTravelMins(int minPairs) {
        try {
            List<Object[]> rows = entityManager.createNativeQuery("""
                    SELECT e2.tile_id,
                           AVG(LEAST(
                               EXTRACT(EPOCH FROM (e2.arrived_at_pickup - e1.pickup_completed_at))/60.0,
                               COALESCE(t.traversal_cap_sec, 600) / 60.0
                           ))
                    FROM shipment_leg_events e1
                    JOIN shipment_leg_events e2
                        ON e1.da_id        = e2.da_id
                       AND e1.shift_date   = e2.shift_date
                       AND e1.tile_id      = e2.tile_id
                       AND e2.stop_sequence = e1.stop_sequence + 1
                    JOIN tile t ON t.id = e2.tile_id
                    WHERE e1.created_at >= now() - interval '7 days'
                      AND e2.arrived_at_pickup > e1.pickup_completed_at
                    GROUP BY e2.tile_id
                    HAVING COUNT(*) >= :minPairs
                    """)
                    .setParameter("minPairs", minPairs)
                    .getResultList();
            Map<UUID, Double> result = new HashMap<>();
            for (Object[] row : rows) {
                result.put(UUID.fromString(row[0].toString()), ((Number) row[1]).doubleValue());
            }
            return result;
        } catch (Exception e) {
            log.debug("M4 inter_stop_travel data unavailable: {}", e.getMessage());
            return Map.of();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public Map<UUID, Integer> loadCurrentOrders(LocalDate date) {
        try {
            List<Object[]> rows = entityManager.createNativeQuery("""
                    SELECT tile_id, COUNT(*)
                    FROM shipment_leg_events
                    WHERE shift_date = :date
                    GROUP BY tile_id
                    """)
                    .setParameter("date", date)
                    .getResultList();
            Map<UUID, Integer> result = new HashMap<>();
            for (Object[] row : rows) {
                result.put(UUID.fromString(row[0].toString()), ((Number) row[1]).intValue());
            }
            return result;
        } catch (Exception e) {
            log.debug("M4 current orders unavailable: {}", e.getMessage());
            return Map.of();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public Map<UUID, Double> loadHistAvgOrders(LocalDate date) {
        try {
            List<Object[]> rows = entityManager.createNativeQuery("""
                    SELECT tile_id, AVG(daily_count)
                    FROM (
                        SELECT tile_id, shift_date, COUNT(*) AS daily_count
                        FROM shipment_leg_events
                        WHERE shift_date >= :startDate AND shift_date < :date
                        GROUP BY tile_id, shift_date
                    ) daily
                    GROUP BY tile_id
                    """)
                    .setParameter("startDate", date.minusDays(7))
                    .setParameter("date", date)
                    .getResultList();
            Map<UUID, Double> result = new HashMap<>();
            for (Object[] row : rows) {
                result.put(UUID.fromString(row[0].toString()), ((Number) row[1]).doubleValue());
            }
            return result;
        } catch (Exception e) {
            log.debug("M4 historical order data unavailable: {}", e.getMessage());
            return Map.of();
        }
    }
}
