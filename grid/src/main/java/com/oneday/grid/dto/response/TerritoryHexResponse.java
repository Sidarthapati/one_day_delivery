package com.oneday.grid.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * One hex of a DA's territory, carrying the day's demand snapshot for it plus its H3 corner
 * vertices. M6 sums {@code demandScoreOrders} / {@code serviceTimeMin} across a DA's hexes for
 * per-territory demand, and treats {@code vertices} as that DA's candidate meeting points
 * (set-cover, §7.1–7.2). Demand fields are 0 when no snapshot exists for the date.
 */
public record TerritoryHexResponse(
        UUID hexId,
        long h3Index,
        double demandScoreOrders,
        double serviceTimeMin,
        List<GridVertexResponse> vertices
) {}
