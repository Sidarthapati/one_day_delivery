package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.domain.TileDemandSnapshot;
import com.oneday.grid.repository.TileDemandSnapshotRepository;
import com.oneday.grid.repository.TileRepository;
import com.oneday.grid.service.DemandScoringService;
import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class DemandScoringServiceImpl implements DemandScoringService {

    private static final Logger log = LoggerFactory.getLogger(DemandScoringServiceImpl.class);

    private final GridService gridService;
    private final TileRepository tileRepository;
    private final TileDemandSnapshotRepository snapshotRepository;
    private final GridProperties properties;
    private final M4DataLoader m4;

    DemandScoringServiceImpl(GridService gridService,
                             TileRepository tileRepository,
                             TileDemandSnapshotRepository snapshotRepository,
                             GridProperties properties,
                             M4DataLoader m4) {
        this.gridService = gridService;
        this.tileRepository = tileRepository;
        this.snapshotRepository = snapshotRepository;
        this.properties = properties;
        this.m4 = m4;
    }

    @Override
    @Transactional
    public List<TileDemandSnapshot> computeAndPersistDemand(UUID cityId, LocalDate date) {
        Grid grid = gridService.getGrid(cityId);
        List<Tile> activeTiles = tileRepository.findByGridIdAndActiveTrue(grid.getId());

        // If snapshots already exist for all active tiles for this date, return them as-is.
        // This preserves manual demand overrides made via the demo endpoint.
        // Deduplicate by tileId so that manual overrides (delete+insert) don't break the check.
        Set<UUID> activeTileIds = activeTiles.stream().map(Tile::getId).collect(Collectors.toSet());
        Map<UUID, TileDemandSnapshot> existingByTile = snapshotRepository.findBySnapshotDate(date).stream()
                .filter(s -> activeTileIds.contains(s.getTileId()))
                .collect(Collectors.toMap(TileDemandSnapshot::getTileId, s -> s, (a, b) -> b));
        if (existingByTile.keySet().containsAll(activeTileIds)) {
            log.info("Demand snapshots already present for cityId={} date={} — skipping recomputation", cityId, date);
            return new ArrayList<>(existingByTile.values());
        }

        // M4 queries run in their own REQUIRES_NEW transactions. The inner catch in
        // M4DataLoader intercepts the SQL error, but Hibernate still marks the REQUIRES_NEW
        // transaction as rollback-only, so Spring throws UnexpectedRollbackException when it
        // tries to commit that inner transaction. We catch it here so the outer transaction
        // is not affected.
        Map<UUID, Double> serviceTimeMins = safeCall(() -> m4.loadServiceTimeMins(properties.getBootstrap().getMinPickupsForRealData()), Map.of());
        Map<UUID, Double> interStopTravelMins = safeCall(() -> m4.loadInterStopTravelMins(properties.getSolver().getMinInterStopPairsPerWindow()), Map.of());
        Map<UUID, Integer> currentOrders = safeCall(() -> m4.loadCurrentOrders(date), Map.of());
        Map<UUID, Double> histAvgOrders = safeCall(() -> m4.loadHistAvgOrders(date), Map.of());

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

    private double cityWideAvg(Map<UUID, Double> values, double bootstrapDefault) {
        if (values.isEmpty()) return bootstrapDefault;
        return values.values().stream().mapToDouble(Double::doubleValue).average().orElse(bootstrapDefault);
    }

    private <T> T safeCall(Supplier<T> fn, T fallback) {
        try {
            return fn.get();
        } catch (Exception e) {
            log.debug("M4 data loader failed (treating as unavailable): {}", e.getMessage());
            return fallback;
        }
    }
}
