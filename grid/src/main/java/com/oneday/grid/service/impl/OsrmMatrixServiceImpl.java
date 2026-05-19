package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.repository.TileRepository;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.OsrmMatrixService;
import com.oneday.grid.service.osrm.OsrmClient;
import com.oneday.grid.service.osrm.TileEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OsrmMatrixServiceImpl implements OsrmMatrixService {

    private static final Logger log = LoggerFactory.getLogger(OsrmMatrixServiceImpl.class);

    private final GridService gridService;
    private final TileRepository tileRepository;
    private final GridProperties properties;
    private final OsrmClient osrmClient;

    OsrmMatrixServiceImpl(GridService gridService,
                          TileRepository tileRepository,
                          GridProperties properties) {
        this.gridService = gridService;
        this.tileRepository = tileRepository;
        this.properties = properties;
        this.osrmClient = new OsrmClient(properties.getOsrm().getBaseUrl(), new RestTemplate());
    }

    @Override
    public Map<UUID, List<TileEdge>> computeAdjacencyMatrix(UUID cityId) {
        Grid grid = gridService.getGrid(cityId);
        List<Tile> activeTiles = tileRepository.findByGridIdAndActiveTrue(grid.getId());

        if (activeTiles.isEmpty()) return Map.of();

        List<double[]> centroids = activeTiles.stream()
                .map(t -> new double[]{
                        grid.getOriginLat() + (t.getRowIdx() + 0.5) * grid.getTileDeltaLat(),
                        grid.getOriginLon() + (t.getColIdx() + 0.5) * grid.getTileDeltaLon()
                })
                .toList();

        double[][] durations = osrmClient.getTable(centroids);
        int threshold = properties.getOsrm().getAdjacencyThresholdSeconds();

        Map<UUID, List<TileEdge>> result = new HashMap<>();
        int isolated = 0;
        for (int i = 0; i < activeTiles.size(); i++) {
            UUID fromId = activeTiles.get(i).getId();
            List<TileEdge> edges = new ArrayList<>();
            for (int j = 0; j < activeTiles.size(); j++) {
                if (i == j) continue;
                double d = durations[i][j];
                if (d > 0 && d <= threshold) {
                    edges.add(new TileEdge(activeTiles.get(j).getId(), (int) d));
                }
            }
            if (edges.isEmpty()) {
                log.warn("ISOLATION_WARNING: tile {} has zero road-reachable neighbors — will get its own DA", fromId);
                isolated++;
            }
            result.put(fromId, edges);
        }
        log.info("computeAdjacencyMatrix: {} active tiles, {} isolated, threshold={}s", activeTiles.size(), isolated, threshold);
        return result;
    }

    @Override
    public Map<UUID, Integer> computeTraversalCaps(UUID cityId) {
        Grid grid = gridService.getGrid(cityId);
        List<Tile> activeTiles = tileRepository.findByGridIdAndActiveTrue(grid.getId());

        Map<UUID, Integer> caps = new HashMap<>();
        for (Tile t : activeTiles) {
            double swLat = grid.getOriginLat() + t.getRowIdx() * grid.getTileDeltaLat();
            double swLon = grid.getOriginLon() + t.getColIdx() * grid.getTileDeltaLon();
            double neLat = swLat + grid.getTileDeltaLat();
            double neLon = swLon + grid.getTileDeltaLon();

            Integer cap = osrmClient.getTileTraversalCap(swLat, swLon, neLat, neLon);
            if (cap != null) caps.put(t.getId(), cap);
        }
        log.info("computeTraversalCaps: {}/{} tiles got a cap", caps.size(), activeTiles.size());
        return caps;
    }
}
