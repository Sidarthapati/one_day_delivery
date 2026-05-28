package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Hex;
import com.oneday.grid.repository.HexRepository;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.OsrmMatrixService;
import com.oneday.grid.service.osrm.OsrmClient;
import com.oneday.grid.service.osrm.TileEdge;
import com.uber.h3core.H3Core;
import com.uber.h3core.LengthUnit;
import com.uber.h3core.util.LatLng;
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
    private final HexRepository hexRepository;
    private final GridProperties properties;
    private final H3Core h3Core;
    private final OsrmClient osrmClient;

    OsrmMatrixServiceImpl(GridService gridService,
                          HexRepository hexRepository,
                          GridProperties properties,
                          H3Core h3Core) {
        this.gridService = gridService;
        this.hexRepository = hexRepository;
        this.properties = properties;
        this.h3Core = h3Core;
        this.osrmClient = new OsrmClient(properties.getOsrm().getBaseUrl(), new RestTemplate());
    }

    @Override
    public Map<UUID, List<TileEdge>> computeAdjacencyMatrix(UUID cityId) {
        Grid grid = gridService.getGrid(cityId);
        List<Hex> activeHexes = hexRepository.findByH3GridIdAndActiveTrue(grid.getId());

        if (activeHexes.isEmpty()) return Map.of();

        List<double[]> centroids = activeHexes.stream()
                .map(h -> {
                    LatLng c = h3Core.cellToLatLng(h.getH3Index());
                    return new double[]{c.lat, c.lng};
                })
                .toList();

        double[][] durations = osrmClient.getTable(centroids);
        int threshold = properties.getOsrm().getAdjacencyThresholdSeconds();

        Map<UUID, List<TileEdge>> result = new HashMap<>();
        int isolated = 0;
        for (int i = 0; i < activeHexes.size(); i++) {
            UUID fromId = activeHexes.get(i).getId();
            List<TileEdge> edges = new ArrayList<>();
            for (int j = 0; j < activeHexes.size(); j++) {
                if (i == j) continue;
                double d = durations[i][j];
                if (d > 0 && d <= threshold) {
                    edges.add(new TileEdge(activeHexes.get(j).getId(), (int) d));
                }
            }
            if (edges.isEmpty()) {
                log.warn("ISOLATION_WARNING: tile {} has zero road-reachable neighbors — will get its own DA", fromId);
                isolated++;
            }
            result.put(fromId, edges);
        }
        log.info("computeAdjacencyMatrix: {} active hexes, {} isolated, threshold={}s",
                activeHexes.size(), isolated, threshold);
        return result;
    }

    @Override
    public Map<UUID, Integer> computeTraversalCaps(UUID cityId) {
        Grid grid = gridService.getGrid(cityId);
        List<Hex> activeHexes = hexRepository.findByH3GridIdAndActiveTrue(grid.getId());

        // Use H3 edge length as the traversal distance; OSRM routes centroid ± half edge.
        double edgeLengthDeg = h3Core.getHexagonEdgeLengthAvg(grid.getH3Resolution(), LengthUnit.m) / 111320.0;

        Map<UUID, Integer> caps = new HashMap<>();
        for (Hex h : activeHexes) {
            LatLng c = h3Core.cellToLatLng(h.getH3Index());
            double swLat = c.lat - edgeLengthDeg / 2;
            double neLat = c.lat + edgeLengthDeg / 2;
            Integer cap = osrmClient.getTileTraversalCap(swLat, c.lng, neLat, c.lng);
            if (cap != null) caps.put(h.getId(), cap);
        }
        log.info("computeTraversalCaps: {}/{} hexes got a cap", caps.size(), activeHexes.size());
        return caps;
    }
}
