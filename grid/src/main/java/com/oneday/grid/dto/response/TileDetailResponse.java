package com.oneday.grid.dto.response;

import java.util.UUID;

public record TileDetailResponse(
        UUID id,
        String h3Index,
        boolean active,
        double centerLat,
        double centerLon,
        double demandScoreOrders,
        double demandScoreMinutes,
        boolean bootstrapped
) {}
