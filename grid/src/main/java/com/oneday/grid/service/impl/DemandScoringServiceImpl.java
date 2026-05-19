package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.domain.TileDemandSnapshot;
import com.oneday.grid.repository.TileDemandSnapshotRepository;
import com.oneday.grid.repository.TileRepository;
import com.oneday.grid.service.DemandScoringService;
import com.oneday.grid.service.GridService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DemandScoringServiceImpl implements DemandScoringService {

    private static final Logger log = LoggerFactory.getLogger(DemandScoringServiceImpl.class);

    private final GridService gridService;
    private final TileRepository tileRepository;
    private final TileDemandSnapshotRepository snapshotRepository;
    private final GridProperties properties;
    private final EntityManager entityManager;

    DemandScoringServiceImpl(GridService gridService,
                             TileRepository tileRepository,
                             TileDemandSnapshotRepository snapshotRepository,
                             GridProperties properties,
                             EntityManager entityManager) {
        this.gridService = gridService;
        this.tileRepository = tileRepository;
        this.snapshotRepository = snapshotRepository;
        this.properties = properties;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public List<TileDemandSnapshot> computeAndPersistDemand(UUID cityId, LocalDate date) {
        Grid grid = gridService.getGrid(cityId);
        List<Tile> activeTiles = tileRepository.findByGridIdAndActiveTrue(grid.getId());

        Map<UUID, Double> serviceTimeMins = loadServiceTimeMins();
        Map<UUID, Double> interStopTravelMins = loadInterStopTravelMins();
        Map<UUID, Integer> currentOrders = loadCurrentOrders(date);
        Map<UUID, Double> histAvgOrders = loadHistAvgOrders(date);

        double cityWideSvcTime = cityWideAvg(serviceTimeMins, properties.getBootstrap().getServiceTimeMin());
        double cityWideInterStop = cityWideAvg(interStopTravelMins, properties.getBootstrap().getInterStopTravelMin());

        boolean bootstrapMode = serviceTimeMins.isEmpty() && interStopTravelMins.isEmpty();
        if (bootstrapMode) {
            log.info("Demand scoring: M4 data unavailable — running in full bootstrap mode for cityId={}", cityId);
        }

        List<TileDemandSnapshot> snapshots = new ArrayList<>(activeTiles.size());
        for (Tile tile : activeTiles) {
            UUID tid = tile.getId();

            boolean svcBootstrapped = !serviceTimeMins.containsKey(tid);
            boolean interBootstrapped = !interStopTravelMins.containsKey(tid);

            double svcTime = svcBootstrapped ? cityWideSvcTime : serviceTimeMins.get(tid);
            double interStop = interBootstrapped ? cityWideInterStop : interStopTravelMins.get(tid);

            double histAvg = histAvgOrders.getOrDefault(tid, 0.0);
            int current = currentOrders.getOrDefault(tid, 0);
            double demandOrders = 0.70 * histAvg + 0.30 * current;
            double orderEngagedMin = svcTime + interStop;
            double demandMinutes = demandOrders * orderEngagedMin;

            snapshots.add(TileDemandSnapshot.builder()
                    .tileId(tid)
                    .snapshotDate(date)
                    .histAvgOrders(histAvg)
                    .currentOrders(current)
                    .demandScoreOrders(demandOrders)
                    .serviceTimeMin(svcTime)
                    .interStopTravelMin(interStop)
                    .orderEngagedMin(orderEngagedMin)
                    .demandScoreMinutes(demandMinutes)
                    .bootstrapped(svcBootstrapped || interBootstrapped)
                    .build());
        }

        return snapshotRepository.saveAll(snapshots);
    }

    // service_time_min per tile: avg(pickup_completed_at - arrived_at_pickup) for tiles
    // with >= minPickups samples in the last 7 days.
    @SuppressWarnings("unchecked")
    private Map<UUID, Double> loadServiceTimeMins() {
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
                    .setParameter("minPickups", properties.getBootstrap().getMinPickupsForRealData())
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

    // inter_stop_travel_min per tile: avg time between consecutive same-DA same-tile pickups,
    // winsorised at traversal_cap_sec to remove detour outliers.
    @SuppressWarnings("unchecked")
    private Map<UUID, Double> loadInterStopTravelMins() {
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
                    .setParameter("minPairs", properties.getSolver().getMinInterStopPairsPerWindow())
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

    @SuppressWarnings("unchecked")
    private Map<UUID, Integer> loadCurrentOrders(LocalDate date) {
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

    @SuppressWarnings("unchecked")
    private Map<UUID, Double> loadHistAvgOrders(LocalDate date) {
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

    private double cityWideAvg(Map<UUID, Double> values, double bootstrapDefault) {
        if (values.isEmpty()) return bootstrapDefault;
        return values.values().stream().mapToDouble(Double::doubleValue).average().orElse(bootstrapDefault);
    }
}
