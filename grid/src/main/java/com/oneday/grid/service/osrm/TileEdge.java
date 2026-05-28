package com.oneday.grid.service.osrm;

import java.util.UUID;

public record TileEdge(UUID toHexId, int travelTimeSec) {}
