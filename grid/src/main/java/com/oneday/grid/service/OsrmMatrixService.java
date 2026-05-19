package com.oneday.grid.service;

import com.oneday.grid.service.osrm.TileEdge;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface OsrmMatrixService {
    // Returns fromTileId → edges within ADJACENCY_THRESHOLD_SECONDS.
    Map<UUID, List<TileEdge>> computeAdjacencyMatrix(UUID cityId);

    // Returns tileId → OSRM road time (seconds) from SW to NE corner per tile.
    // Used by OsrmMatrixRefreshJob to populate tile.traversal_cap_sec.
    Map<UUID, Integer> computeTraversalCaps(UUID cityId);
}
