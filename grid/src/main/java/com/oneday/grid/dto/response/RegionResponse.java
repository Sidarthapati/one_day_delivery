package com.oneday.grid.dto.response;

import java.util.List;
import java.util.UUID;

public record RegionResponse(
        UUID id,
        UUID daId,
        int nDasRequired,
        double estimatedDemandMin,
        double estimatedUtilPct,
        boolean hasBootstrappedTiles,
        List<UUID> tileIds
) {}
