package com.oneday.routing.service;

import com.oneday.routing.service.model.DaTerritory;
import com.oneday.routing.service.model.TerritoryDemand;
import com.oneday.routing.service.model.TerritoryHex;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stage 1 of the nightly pipeline (§7.1, M6-D-004): per ACTIVE DA, sum the day's
 * {@code demand_score_orders} + {@code service_time_min} over its hexes. The daily volume splits
 * into first-mile (collect) and last-mile (deliver) halves — symmetric 50/50 in v1 (Q3); when M4/M7
 * land, the real first/last-mile ratio replaces it. No demand is recomputed — M6 reuses M3's 70/30
 * snapshot, keeping one demand truth across the modules.
 */
@Service
public class DemandAggregationService {

    public List<TerritoryDemand> aggregate(List<DaTerritory> territories) {
        return territories.stream().map(DemandAggregationService::aggregateOne).toList();
    }

    private static TerritoryDemand aggregateOne(DaTerritory territory) {
        double dailyDemand = 0.0;
        double serviceTime = 0.0;
        for (TerritoryHex hex : territory.hexes()) {
            dailyDemand += hex.demandScoreOrders();
            serviceTime += hex.serviceTimeMin();
        }
        double half = dailyDemand / 2.0;
        return new TerritoryDemand(territory.daId(), dailyDemand, serviceTime, half, half);
    }
}
