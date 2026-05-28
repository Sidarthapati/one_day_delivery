package com.oneday.grid.service;

import com.oneday.grid.service.osrm.TileEdge;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface OsrmMatrixService {
    // Returns fromHexId → edges within ADJACENCY_THRESHOLD_SECONDS.
    Map<UUID, List<TileEdge>> computeAdjacencyMatrix(UUID cityId);

    // Returns hexId → OSRM road time (seconds) from SW to NE corner per hex.
    // Used by OsrmMatrixRefreshJob to populate hex.traversal_cap_sec.
    Map<UUID, Integer> computeTraversalCaps(UUID cityId);
}
