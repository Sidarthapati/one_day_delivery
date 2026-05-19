package com.oneday.grid.dto.response;

import java.util.UUID;

// Full tile descriptor for the map UI.
// Bounds form the SW and NE corners of the 2×2 km cell.
// demandScoreOrders is 0.0 when no snapshot exists for the requested date.
public record TileDetailResponse(
        UUID id,
        int rowIdx,
        int colIdx,
        boolean active,
        double swLat,
        double swLon,
        double neLat,
        double neLon,
        double demandScoreOrders,
        boolean bootstrapped
) {}
