package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Hex;
import com.oneday.grid.domain.HexDemandSnapshot;
import com.oneday.grid.repository.HexDemandSnapshotRepository;
import com.oneday.grid.repository.HexRepository;
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
    private final HexRepository hexRepository;
    private final HexDemandSnapshotRepository snapshotRepository;
    private final GridProperties properties;
    private final M4DataLoader m4;

    DemandScoringServiceImpl(GridService gridService,
                             HexRepository hexRepository,
                             HexDemandSnapshotRepository snapshotRepository,
                             GridProperties properties,
                             M4DataLoader m4) {
        this.gridService = gridService;
        this.hexRepository = hexRepository;
        this.snapshotRepository = snapshotRepository;
        this.properties = properties;
        this.m4 = m4;
    }

    @Override
    @Transactional
    public List<HexDemandSnapshot> computeAndPersistDemand(UUID cityId, LocalDate date) {
        Grid grid = gridService.getGrid(cityId);
        List<Hex> activeHexes = hexRepository.findByH3GridIdAndActiveTrue(grid.getId());

        Set<UUID> activeHexIds = activeHexes.stream().map(Hex::getId).collect(Collectors.toSet());
        Map<UUID, HexDemandSnapshot> existingByHex = snapshotRepository.findBySnapshotDate(date).stream()
                .filter(s -> activeHexIds.contains(s.getHexId()))
                .collect(Collectors.toMap(HexDemandSnapshot::getHexId, s -> s, (a, b) -> b));
        if (existingByHex.keySet().containsAll(activeHexIds)) {
            log.info("Demand snapshots already present for cityId={} date={} — skipping recomputation", cityId, date);
            return new ArrayList<>(existingByHex.values());
        }

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

        List<HexDemandSnapshot> snapshots = new ArrayList<>(activeHexes.size());
        for (Hex hex : activeHexes) {
            UUID hid = hex.getId();

            boolean svcBootstrapped = !serviceTimeMins.containsKey(hid);
            boolean interBootstrapped = !interStopTravelMins.containsKey(hid);

            double svcTime = svcBootstrapped ? cityWideSvcTime : serviceTimeMins.get(hid);
            double interStop = interBootstrapped ? cityWideInterStop : interStopTravelMins.get(hid);

            double histAvg = histAvgOrders.getOrDefault(hid, 0.0);
            int current = currentOrders.getOrDefault(hid, 0);
            double demandOrders = 0.70 * histAvg + 0.30 * current;
            double orderEngagedMin = svcTime + interStop;
            double demandMinutes = demandOrders * orderEngagedMin;

            snapshots.add(HexDemandSnapshot.builder()
                    .hexId(hid)
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
