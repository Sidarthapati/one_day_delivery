package com.oneday.routing.service.model;

import java.util.UUID;

/**
 * Per-DA aggregated demand for the day (§7.1, M6-D-004). {@code dailyDemandOrders} and
 * {@code serviceTimeMin} are summed over the DA's hexes; the daily volume is split into
 * first-mile (collect) and last-mile (deliver) quantities — symmetric 50/50 in v1 (Q3).
 */
public record TerritoryDemand(
        UUID daId,
        double dailyDemandOrders,
        double serviceTimeMin,
        double firstMileQty,
        double lastMileQty
) {}
