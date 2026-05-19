package com.oneday.grid.batch;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.domain.TileTravelTime;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.TileRepository;
import com.oneday.grid.repository.TileTravelTimeRepository;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.OsrmMatrixService;
import com.oneday.grid.service.osrm.TileEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Admin-triggered via POST /grid/admin/osrm-refresh?city_id=...
// Also runs monthly (1st of month, 02:00 IST) to keep travel times current.
// This is the only job that calls OSRM — the nightly replan reads tile_travel_time from DB.
@Component
public class OsrmMatrixRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(OsrmMatrixRefreshJob.class);

    private final GridService gridService;
    private final GridRepository gridRepository;
    private final OsrmMatrixService osrmMatrixService;
    private final TileTravelTimeRepository travelTimeRepository;
    private final TileRepository tileRepository;
    private final GridProperties properties;

    OsrmMatrixRefreshJob(GridService gridService,
                         GridRepository gridRepository,
                         OsrmMatrixService osrmMatrixService,
                         TileTravelTimeRepository travelTimeRepository,
                         TileRepository tileRepository,
                         GridProperties properties) {
        this.gridService = gridService;
        this.gridRepository = gridRepository;
        this.osrmMatrixService = osrmMatrixService;
        this.travelTimeRepository = travelTimeRepository;
        this.tileRepository = tileRepository;
        this.properties = properties;
    }

    @Scheduled(cron = "0 0 2 1 * *", zone = "Asia/Kolkata")
    public void runMonthly() {
        log.info("OsrmMatrixRefreshJob monthly run starting for all cities");
        for (Grid grid : gridRepository.findAll()) {
            try {
                refresh(grid.getCityId());
            } catch (Exception e) {
                log.error("OsrmMatrixRefreshJob failed for cityId={}", grid.getCityId(), e);
            }
        }
    }

    @Transactional
    public void refresh(UUID cityId) {
        Grid grid = gridService.getGrid(cityId);
        log.info("OsrmMatrixRefreshJob refresh starting for cityId={} gridId={}", cityId, grid.getId());

        Map<UUID, List<TileEdge>> adjacency = osrmMatrixService.computeAdjacencyMatrix(cityId);
        Map<UUID, Integer> traversalCaps = osrmMatrixService.computeTraversalCaps(cityId);

        // Replace travel-time matrix wholesale (not append-only).
        travelTimeRepository.deleteByGridId(grid.getId());

        List<TileTravelTime> newEdges = new ArrayList<>();
        int isolated = 0;
        for (Map.Entry<UUID, List<TileEdge>> entry : adjacency.entrySet()) {
            UUID fromTileId = entry.getKey();
            List<TileEdge> edges = entry.getValue();
            if (edges.isEmpty()) isolated++;
            for (TileEdge edge : edges) {
                newEdges.add(TileTravelTime.builder()
                        .gridId(grid.getId())
                        .fromTileId(fromTileId)
                        .toTileId(edge.toTileId())
                        .travelTimeSeconds(edge.travelTimeSec())
                        .build());
            }
        }
        travelTimeRepository.saveAll(newEdges);

        // Update traversal caps on each active tile.
        List<Tile> activeTiles = tileRepository.findByGridIdAndActiveTrue(grid.getId());
        for (Tile tile : activeTiles) {
            Integer cap = traversalCaps.get(tile.getId());
            if (cap != null) {
                tile.setTraversalCapSec(cap);
            }
        }
        tileRepository.saveAll(activeTiles);

        log.info("OsrmMatrixRefreshJob complete for cityId={}: {} edges persisted, {} tiles updated, {} isolated",
                cityId, newEdges.size(), traversalCaps.size(), isolated);

        if (isolated > 0) {
            log.warn("ISOLATION_WARNING: {} tiles in cityId={} have 0 OSRM-reachable neighbours — each will get its own DA",
                    isolated, cityId);
        }
    }
}
